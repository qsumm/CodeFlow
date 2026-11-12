import { useRef, useState } from 'react';
import { Form, message } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  createSkill, createSkillFromPackage, inspectSkillPackage, setSkillCategory,
  updateSkill, updateSkillPackage,
} from '../api';
import type { Skill } from '../api';
import { buildDirectoryZip } from '../skillPackage';
import {
  errorMessage, mcpValues, pluginProviders, serializeMcpValues, UNCATEGORIZED,
} from '../skillFormat';

const MAX_SKILL_PACKAGE_BYTES = 100 * 1024 * 1024;

/**
 * 技能新建/编辑表单的状态与提交逻辑。
 * 表单弹窗只负责渲染，保存、打标与包上传的时序都在这里收口。
 */
export function useSkillForm({ invalidateSkills }: { invalidateSkills: () => void }) {
  const runWithAccess = useAccessCommand();
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const selectedType = Form.useWatch('type', form);

  const [formOpen, setFormOpen] = useState(false);
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [accessMode, setAccessMode] = useState<'manual' | 'package'>('manual');
  const [packageReading, setPackageReading] = useState(false);
  const [zipFile, setZipFile] = useState<File | null>(null);
  // 包选择的自增序号：作废在途的目录打包/解析结果，避免旧结果覆盖新选择
  const packageSelectionSeq = useRef(0);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const zipInputRef = useRef<HTMLInputElement | null>(null);

  const createMut = useMutation({ mutationFn: createSkill });

  const updateMut = useMutation({
    mutationFn: ({ id, data: d }: { id: number; data: Parameters<typeof updateSkill>[1] }) => updateSkill(id, d),
  });

  const createPackageMut = useMutation({
    mutationFn: ({ file, metadata }: { file: File; metadata: Parameters<typeof createSkillFromPackage>[1] }) => createSkillFromPackage(file, metadata),
  });

  const updatePackageMut = useMutation({
    mutationFn: ({ id, file, metadata }: { id: number; file: File; metadata: Parameters<typeof updateSkillPackage>[2] }) => updateSkillPackage(id, file, metadata),
  });

  /** 丢弃当前包选择：作废在途解析并清空已选文件。 */
  const resetPackageSelection = () => {
    packageSelectionSeq.current += 1;
    setPackageReading(false);
    setZipFile(null);
  };

  const openCreate = () => {
    runWithAccess('READ_WRITE', i18n.t('skill:create'), () => {
      setEditingSkill(null);
      setAccessMode('manual');
      resetPackageSelection();
      form.resetFields();
      form.setFieldsValue({ type: 'SKILL' });
      setFormOpen(true);
    });
  };

  const openEdit = (skill: Skill) => {
    runWithAccess('READ_WRITE', i18n.t('skill:edit'), () => {
      setEditingSkill(skill);
      setAccessMode(skill.sourceType === 'OSS_ZIP' ? 'package' : 'manual');
      resetPackageSelection();
      let mcpConfig: Record<string, unknown> = {};
      if (skill.type === 'MCP') {
        try {
          mcpConfig = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
        } catch {
          mcpConfig = {};
        }
      }
      form.setFieldsValue({
        type: skill.type,
        name: skill.name,
        installSpec: skill.installSpec,
        description: skill.description,
        categoryId: skill.categoryId == null ? UNCATEGORIZED : String(skill.categoryId),
        mcpTransport: mcpConfig.transport || 'http',
        mcpUrl: mcpConfig.url,
        mcpCommand: mcpConfig.command,
        mcpArgs: Array.isArray(mcpConfig.args)
          ? mcpConfig.args.map((value) => ({ value: String(value) }))
          : [],
        mcpHeaders: mcpValues(mcpConfig.headers),
        mcpEnv: mcpValues(mcpConfig.env),
        mcpTimeoutSeconds: mcpConfig.timeoutSeconds || 60,
        providers: skill.type === 'PLUGIN' ? pluginProviders(skill.installSpec) : undefined,
      });
      setFormOpen(true);
    });
  };

  const closeForm = () => {
    setFormOpen(false);
    setEditingSkill(null);
    resetPackageSelection();
    form.resetFields();
  };

  // 保存能力后链式设置分类：值没变就不请求；新建且选「未分类」也跳过（默认即未分类）。
  // 打标失败不影响已保存的能力内容，但要明确提示。
  const applyCategoryTag = async (skillId: number, categoryValue: unknown) => {
    const target = categoryValue == null || categoryValue === UNCATEGORIZED ? null : Number(categoryValue);
    const before = editingSkill?.categoryId ?? null;
    if (!editingSkill && target === null) return;
    if (editingSkill && target === before) return;
    try {
      await setSkillCategory(skillId, target);
      invalidateSkills();
    } catch (error) {
      message.error(i18n.t('skill:category.savedTagFailed', { reason: errorMessage(error) }));
    }
  };

  // 成功后的收尾（关表单、提示、刷新）统一放在 handleSubmit：保存能力后还要链式设置分类，
  // 若在各 mutation 的 onSuccess 里各自处理，无法保证两条请求的先后与失败提示；
  // 列表刷新必须无条件执行——分类未变时 applyCategoryTag 不会刷新，再次编辑会读到旧行。
  const handleSubmit = async () => {
    await runWithAccess('READ_WRITE', editingSkill ? i18n.t('skill:edit') : i18n.t('skill:create'), async () => {
      if (accessMode === 'package') {
        if (packageReading || !zipFile) {
          message.error(i18n.t('skill:package.selectFirst'));
          return;
        }
        const values = await form.validateFields().catch(() => null);
        if (!values) return;
        const metadata = { type: values.type, name: values.name, description: values.description, providers: values.providers };
        try {
          const skill = editingSkill
            ? await updatePackageMut.mutateAsync({ id: editingSkill.id, file: zipFile, metadata })
            : await createPackageMut.mutateAsync({ file: zipFile, metadata });
          await invalidateSkills();
          await applyCategoryTag(skill.id, values.categoryId);
          message.success(editingSkill ? i18n.t('skill:package.replaced') : i18n.t('skill:package.uploaded'));
          closeForm();
        } catch (error) {
          message.error(errorMessage(error));
        }
        return;
      }
      const values = await form.validateFields().catch(() => null);
      if (!values) return;
      if (values.type === 'MCP') {
        const headers = serializeMcpValues(values.mcpHeaders);
        const env = serializeMcpValues(values.mcpEnv);
        values.installSpec = JSON.stringify(values.mcpTransport === 'stdio'
          ? {
            transport: 'stdio',
            command: values.mcpCommand,
            args: (values.mcpArgs || []).map((item: { value?: string }) => item.value?.trim()).filter(Boolean),
            env,
          }
          : {
            transport: values.mcpTransport || 'http',
            url: values.mcpUrl,
            headers,
            timeoutSeconds: values.mcpTimeoutSeconds || 60,
          });
      }
      try {
        const skill = editingSkill
          ? await updateMut.mutateAsync({
            id: editingSkill.id,
            data: {
              name: values.name,
              installSpec: values.installSpec,
              description: values.description,
            },
          })
          : await createMut.mutateAsync(values);
        await invalidateSkills();
        await applyCategoryTag(skill.id, values.categoryId);
        message.success(editingSkill ? i18n.t('skill:saved') : i18n.t('skill:created'));
        closeForm();
      } catch (error) {
        message.error(errorMessage(error));
      }
    });
  };

  const handlePackageSelectFiles = async (files: FileList | null, directory: boolean) => {
    if (!files?.length) return;
    const seq = ++packageSelectionSeq.current;
    setZipFile(null);
    setPackageReading(true);
    if (selectedType === 'SKILL') form.setFieldsValue({ name: '', description: '' });
    try {
      const file = directory ? await buildDirectoryZip(files) : files[0];
      if (!file.name.toLowerCase().endsWith('.zip')) throw new Error(i18n.t('skill:package.zipRequired'));
      if (file.size > MAX_SKILL_PACKAGE_BYTES) throw new Error(i18n.t('skill:package.archiveLimit'));
      const metadata = selectedType === 'SKILL' ? await inspectSkillPackage(file) : null;
      if (seq !== packageSelectionSeq.current) return;
      setZipFile(file);
      if (metadata) form.setFieldsValue({ name: metadata.name, description: metadata.description });
    } catch (e) {
      if (seq === packageSelectionSeq.current) message.error(errorMessage(e));
    } finally {
      if (seq === packageSelectionSeq.current) setPackageReading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
      if (zipInputRef.current) zipInputRef.current.value = '';
    }
  };

  return {
    form,
    formOpen,
    editingSkill,
    accessMode,
    setAccessMode,
    packageReading,
    zipFile,
    selectedType,
    fileInputRef,
    zipInputRef,
    submitting: packageReading || createMut.isPending || updateMut.isPending
      || createPackageMut.isPending || updatePackageMut.isPending,
    resetPackageSelection,
    openCreate,
    openEdit,
    closeForm,
    handleSubmit,
    handlePackageSelectFiles,
  };
}
