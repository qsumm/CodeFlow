import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useEffect, useMemo, useState } from 'react';
import { type FocusEvent, useRef, type KeyboardEvent } from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import {
  Alert, Button, Form, Input, Modal, Popconfirm, Result, Select,
  Empty, Space, Spin, Tag, message} from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  useAgent, useAgentVersion, useEditConfig, useSubmitForReview, useUpdateAgent,
  useAddRepoPerm, useRemoveRepoPerm, useAddSkill, useRemoveSkill,
} from './hooks';
import { listRepos } from '@/features/repo/api';
import { listSkills } from '@/features/skill/api';
import { listSdlcTemplates } from '@/features/sdlc/api';
import { getRoleCodeByName, getRoleNameByCode } from './constants';
import type { ColumnsType } from 'antd/es/table';
import type { EvolutionMode, UpdateAgentRequest, UpdateConfigRequest } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAgentEnvironmentVariables } from './useAgentEnvironmentVariables';
import { AgentBasicsCard } from './components/AgentBasicsCard';
import type { AgentFormValues } from './components/AgentBasicsCard';
import { AgentRepoAccessCard } from './components/AgentRepoAccessCard';
import { AgentCapabilitiesCard } from './components/AgentCapabilitiesCard';
import { AgentEnvironmentCard } from './components/AgentEnvironmentCard';
import type { RepoPermRow, SkillRow } from './agentEditTypes';
import type { AgentEnvironmentVariableRef } from './api';

function extractList<T>(value: T[] | { list?: T[] } | undefined): T[] {
  if (!value) return [];
  return Array.isArray(value) ? value : (value.list ?? []);
}

function uniqueBy<T>(items: T[], keyOf: (item: T) => string | number): T[] {
  return [...new Map(items.map(item => [keyOf(item), item])).values()];
}

function errorMessage(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

function evolutionModeFromIdentity(identityJson?: string | null): EvolutionMode {
  if (!identityJson) return 'ASSISTED';
  try {
    const parsed = JSON.parse(identityJson) as { evolutionMode?: string };
    if (parsed.evolutionMode === 'MANUAL' || parsed.evolutionMode === 'ASSISTED' || parsed.evolutionMode === 'AUTO_PROPOSAL') {
      return parsed.evolutionMode;
    }
  } catch {
    // Ignore malformed legacy identity JSON and fall back to the safe assisted mode.
  }
  return 'ASSISTED';
}


/** name/avatarUrl belong to the agent row and are patched separately, never sent to /config. */
function versionConfig(values: AgentFormValues): UpdateConfigRequest {
  return {
    roleName: values.roleName,
    roleCode: values.roleCode,
    businessBackground: values.businessBackground,
    responsibilities: values.responsibilities,
    sdlcId: values.sdlcId,
    evolutionMode: values.evolutionMode,
  };
}

export function AgentEditPage() {
  const { t } = useTranslation('agent');
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const agentId = Number(id);
  const [form] = Form.useForm();
  const businessBackgroundRef = useRef<TextAreaRef>(null);
  const accessCommand = useAccessCommand();

  const { data: agent, isLoading, isError } = useAgent(agentId);
  useLocalizedValidation(form, Boolean(agentId) && !Number.isNaN(agentId) && !isLoading && !isError && Boolean(agent));
  const latestVersionNo = agent?.latestVersionNo ?? 0;
  const {
    data: versionDetail,
    isLoading: isVersionDetailLoading,
    isFetching: isVersionDetailFetching,
    isError: isVersionDetailError,
    error: versionDetailError,
    refetch: refetchVersionDetail,
  } = useAgentVersion(agentId, latestVersionNo);
  const isPlatform = agent?.kind === 'PLATFORM';
  const env = useAgentEnvironmentVariables({
    agentId,
    versionDetail,
    versionLoading: isVersionDetailLoading,
    versionError: isVersionDetailError,
    latestVersionNo,
  });

  const editConfig = useEditConfig();
  const updateAgent = useUpdateAgent();
  const submitForReview = useSubmitForReview();
  const addRepoPerm = useAddRepoPerm();
  const removeRepoPerm = useRemoveRepoPerm();
  const addSkillMut = useAddSkill();
  const removeSkillMut = useRemoveSkill();

  // Local state for relation tables (bound to agent's current relations)
  const [repoPerms, setRepoPerms] = useState<RepoPermRow[]>([]);
  const [skills, setSkills] = useState<SkillRow[]>([]);
  const [saveFeedback, setSaveFeedback] = useState<boolean | null>(null);

  // Selector state
  const [repoModalOpen, setRepoModalOpen] = useState(false);
  const [skillModalOpen, setSkillModalOpen] = useState(false);
  const [selectedRepoIds, setSelectedRepoIds] = useState<number[]>([]);
  const [selectedPermLevel, setSelectedPermLevel] = useState('READ');
  const [selectedBranchPatterns, setSelectedBranchPatterns] = useState<string[]>([]);
  const [editingRepoPerm, setEditingRepoPerm] = useState<RepoPermRow | null>(null);
  const [editingPermLevel, setEditingPermLevel] = useState('READ');
  const [editingBranchPatterns, setEditingBranchPatterns] = useState<string[]>([]);
  const [selectedSkillIds, setSelectedSkillIds] = useState<number[]>([]);

  // Reference data — backend returns raw arrays; API types say PageResult but runtime is T[]
  const { data: reposRaw } = useQuery({ queryKey: ['repos', 1, 100], queryFn: () => listRepos({ page: 1, size: 100 }) });
  const { data: skillsRaw } = useQuery({ queryKey: ['skills', 1, 100], queryFn: () => listSkills({ page: 1, size: 100 }) });
  const { data: sdlcsRaw } = useQuery({ queryKey: ['sdlcs', 1, 100], queryFn: () => listSdlcTemplates({ page: 1, size: 100 }) });
  // Safe extract: handle both PageResult and raw array
  const reposList = useMemo(() => extractList(reposRaw), [reposRaw]);
  const skillsList = useMemo(() => extractList(skillsRaw), [skillsRaw]);
  const sdlcsList = useMemo(() => extractList(sdlcsRaw), [sdlcsRaw]);


  useEffect(() => {
    if (agent) {
      form.setFieldsValue({ name: agent.name, avatarUrl: agent.avatarUrl ?? '' });
    }
  }, [agent, form]);

  useEffect(() => {
    if (versionDetail) {
      form.setFieldsValue({
        roleName: versionDetail.roleName,
        roleCode: versionDetail.roleCode,
        businessBackground: versionDetail.businessBackground,
        responsibilities: versionDetail.responsibilities,
        sdlcId: versionDetail.sdlcId,
        evolutionMode: versionDetail.evolutionMode || evolutionModeFromIdentity(versionDetail.identityJson),
      });
    }
  }, [versionDetail, form]);

  useEffect(() => {
    if (!versionDetail) return;

    setRepoPerms(uniqueBy((versionDetail.repoPerms ?? []).map((perm) => {
      const repo = reposList.find((item) => item.id === perm.repoId);
      return {
        repoId: perm.repoId,
        repoName: repo?.name || `#${perm.repoId}`,
        permLevel: perm.permLevel,
        allowedBranchPatterns: perm.allowedBranchPatterns ?? [],
      };
    }), item => item.repoId));

    setSkills(uniqueBy((versionDetail.skills ?? []).map((skillRef) => {
      const skill = skillsList.find((item) => item.id === skillRef.skillId);
      return {
        skillId: skillRef.skillId,
        // 本地技能列表只取第一页，优先用服务端解析出的名称，避免已绑定能力退化成 #id
        skillName: skillRef.skillName || skill?.name || `#${skillRef.skillId}`,
        type: skill?.type || 'SKILL',
        version: skill?.version,
      };
    }), item => item.skillId));

  }, [versionDetail, reposList, skillsList]);

  if (!agentId || isNaN(agentId)) return (
    <Result status="404" title={t('errors.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError || !agent) return (
    <Result status="error" title={t('errors.load')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );

  // Only patch when a value actually changed: an unconditional PATCH would bump the agent's
  // optimistic-lock version on every draft save and can trip a spurious version conflict.
  const buildAgentPatch = (values: AgentFormValues): UpdateAgentRequest => {
    const patch: UpdateAgentRequest = {};
    const name = (values.name ?? '').trim();
    if (name && name !== agent.name) {
      patch.name = name;
    }
    const avatarUrl = (values.avatarUrl ?? '').trim();
    if (avatarUrl !== (agent.avatarUrl ?? '')) {
      patch.avatarUrl = avatarUrl === '' ? null : avatarUrl;
    }
    return patch;
  };

  const persistForm = async (values: AgentFormValues): Promise<boolean> => {
    const patch = buildAgentPatch(values);
    const agentPatched = Object.keys(patch).length > 0;
    if (agentPatched) {
      await updateAgent.mutateAsync({ id: agentId, payload: patch });
    }
    await editConfig.mutateAsync({ agentId, config: versionConfig(values) });
    return agentPatched;
  };

  const handleSave = () => {
    if (env.hasPendingServerOperation()) {
      message.warning(i18n.t('edit.mountPendingSave', { ns: 'agent' }));
      return;
    }
    accessCommand('READ_WRITE', t('actions.saveConfig'), async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        const agentPatched = await persistForm(values);
        message.success(i18n.t(agentPatched ? 'edit.nameConfigSaved' : 'edit.configSaved', { ns: 'agent' }));
        setSaveFeedback(agentPatched);
      } catch (e: unknown) {
        if (e instanceof Error && e.message) message.error(e.message);
      }
    });
  };

  const handleSubmit = () => {
    if (env.hasPendingServerOperation()) {
      message.warning(i18n.t('edit.mountPendingSubmit', { ns: 'agent' }));
      return;
    }
    accessCommand('READ_WRITE', t('actions.submitReview'), async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        await persistForm(values);
        await submitForReview.mutateAsync(agentId);
        message.success(i18n.t('edit.submitted', { ns: 'agent' }));
        navigate(`/agents/${agentId}`);
      } catch (e: unknown) {
        const msg = errorMessage(e, i18n.t('errors.submit', { ns: 'agent' }));
        message.error(msg);
      }
    });
  };

  const handleRoleNameSelect = (value: string) => {
    const roleCode = getRoleCodeByName(value);
    if (roleCode) {
      form.setFieldsValue({ roleCode });
    }
  };

  const handleRoleCodeSelect = (value: string) => {
    const roleName = getRoleNameByCode(value);
    if (roleName) {
      form.setFieldsValue({ roleName });
    }
  };

  const handleRoleCodeBlur = (e: FocusEvent<HTMLInputElement>) => {
    const normalized = e.target.value.trim().toUpperCase();
    form.setFieldsValue({ roleCode: normalized });
  };

  const handleRoleCodeKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === ' ') {
      e.preventDefault();
      const normalized = e.currentTarget.value.trim().toUpperCase();
      form.setFieldsValue({ roleCode: normalized });
      businessBackgroundRef.current?.focus();
    }
  };

  const handleAddRepo = () => {
    if (selectedRepoIds.length === 0) return;
    accessCommand('READ_WRITE', t('actions.addRepo'), async () => {
      try {
        await Promise.all(selectedRepoIds.map(repoId =>
          addRepoPerm.mutateAsync({ agentId, repoId, permLevel: selectedPermLevel, allowedBranchPatterns: selectedBranchPatterns })));
        const added = selectedRepoIds.map(repoId => {
          const repo = reposList.find(r => r.id === repoId);
          return { repoId, repoName: repo?.name || `#${repoId}`, permLevel: selectedPermLevel, allowedBranchPatterns: selectedBranchPatterns };
        });
        setRepoPerms(prev => uniqueBy([...prev, ...added], item => item.repoId));
        setRepoModalOpen(false);
        setSelectedRepoIds([]);
        setSelectedBranchPatterns([]);
      } catch (e) {
        message.error(errorMessage(e, i18n.t('errors.addRepo', { ns: 'agent' })));
      }
    });
  };

  const openEditRepoPerm = (repo: RepoPermRow) => {
    accessCommand('READ_WRITE', t('actions.editRepo'), () => {
      setEditingRepoPerm(repo);
      setEditingPermLevel(repo.permLevel);
      setEditingBranchPatterns(repo.allowedBranchPatterns);
    });
  };

  const handleEditRepoPerm = () => {
    if (!editingRepoPerm) return;
    accessCommand('READ_WRITE', t('actions.editRepo'), async () => {
      try {
        await addRepoPerm.mutateAsync({
          agentId,
          repoId: editingRepoPerm.repoId,
          permLevel: editingPermLevel,
          allowedBranchPatterns: editingBranchPatterns,
        });
        setRepoPerms(previous => previous.map(repo => repo.repoId === editingRepoPerm.repoId
          ? { ...repo, permLevel: editingPermLevel, allowedBranchPatterns: editingBranchPatterns }
          : repo));
        setEditingRepoPerm(null);
      } catch (e) {
        message.error(errorMessage(e, i18n.t('errors.editRepo', { ns: 'agent' })));
      }
    });
  };

  const handleRemoveRepo = (repoId: number) => {
    accessCommand('READ_WRITE', t('actions.removeRepo'), () => {
      removeRepoPerm.mutate({ agentId, repoId }, {
        onSuccess: () => setRepoPerms(prev => prev.filter(r => r.repoId !== repoId)),
        onError: (e) => message.error(errorMessage(e, i18n.t('errors.removeRepo', { ns: 'agent' }))),
      });
    });
  };

  const handleAddSkill = () => {
    if (selectedSkillIds.length === 0) return;
    accessCommand('READ_WRITE', t('actions.addCapability'), async () => {
      try {
        await Promise.all(selectedSkillIds.map(skillId => addSkillMut.mutateAsync({ agentId, skillId })));
        const added = selectedSkillIds.map(skillId => {
          const skill = skillsList.find(s => s.id === skillId);
          return {
            skillId,
            skillName: skill?.name || `#${skillId}`,
            type: skill?.type || 'SKILL',
            version: skill?.version,
          };
        });
        setSkills(prev => uniqueBy([...prev, ...added], item => item.skillId));
        setSkillModalOpen(false);
        setSelectedSkillIds([]);
      } catch (e) {
        message.error(errorMessage(e, i18n.t('errors.addCapability', { ns: 'agent' })));
      }
    });
  };

  const handleRemoveSkill = (skillId: number) => {
    accessCommand('READ_WRITE', t('actions.removeCapability'), () => {
      removeSkillMut.mutate({ agentId, skillId }, {
        onSuccess: () => setSkills(prev => prev.filter(s => s.skillId !== skillId)),
        onError: (e) => message.error(errorMessage(e, i18n.t('errors.removeCapability', { ns: 'agent' }))),
      });
    });
  };

  const permissionLabels = new Map<string, string>([
    ['READ', t('permissions.READ')], ['WRITE', t('permissions.WRITE')], ['ADMIN', t('permissions.ADMIN')],
  ]);
  const capabilityTypeLabels = new Map<string, string>([
    ['SKILL', t('capabilityTypes.SKILL')], ['MCP', t('capabilityTypes.MCP')],
    ['PLUGIN', t('capabilityTypes.PLUGIN')], ['HOOK', t('capabilityTypes.HOOK')],
  ]);
  const sdlcStatusLabels = new Map<string, string>([
    ['DRAFT', t('sdlcStatus.DRAFT')], ['ENABLED', t('sdlcStatus.ENABLED')], ['DISABLED', t('sdlcStatus.DISABLED')],
  ]);
  const repoColumns: ColumnsType<RepoPermRow> = [
    { title: t('fields.repository'), dataIndex: 'repoName' },
    { title: t('fields.permission'), dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{permissionLabels.get(v) || v}</Tag> },
    ...(isPlatform ? [] : ([
      {
        title: t('fields.commitBranches'), dataIndex: 'allowedBranchPatterns',
        render: (patterns: string[]) => patterns.length === 0
          ? <span style={{ color: 'var(--cf-muted)' }}>{t('repository.unrestricted')}</span>
          : <Space size={[0, 4]} wrap>{patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>)}</Space>,
      },
      {
        title: t('fields.actions'), width: 120,
        render: (_: unknown, r: RepoPermRow) => (
          <Space size={0}>
            <Button type="link" size="small" aria-label={t('repository.editBranchesLabel', { name: r.repoName })}
              icon={<EditOutlined aria-hidden="true" />} onClick={() => openEditRepoPerm(r)} />
            <Popconfirm title={t('actions.confirmRemove')} onConfirm={() => handleRemoveRepo(r.repoId)}>
              <Button type="link" size="small" danger aria-label={t('repository.removeLabel', { name: r.repoName })} icon={<DeleteOutlined aria-hidden="true" />} />
            </Popconfirm>
          </Space>
        ),
      },
    ] as ColumnsType<RepoPermRow>)),
  ];

  const skillColumns: ColumnsType<SkillRow> = [
    { title: t('fields.capability'), dataIndex: 'skillName' },
    { title: t('fields.type'), dataIndex: 'type', width: 110, render: (value: string) => <Tag>{capabilityTypeLabels.get(value) || value}</Tag> },
    { title: t('fields.version'), dataIndex: 'version', width: 80, render: (value?: number) => value ?? '-' },
    {
      title: t('fields.actions'), width: 80,
      render: (_: unknown, r: SkillRow) => (
        <Popconfirm title={t('actions.confirmRemove')} onConfirm={() => handleRemoveSkill(r.skillId)}>
          <Button type="link" size="small" danger aria-label={t('capability.removeLabel', { name: r.skillName })} icon={<DeleteOutlined aria-hidden="true" />} />
        </Popconfirm>
      ),
    },
  ];

  const environmentVariableColumns: ColumnsType<AgentEnvironmentVariableRef> = [
    { title: t('fields.variableName'), dataIndex: 'name', align: 'left', width: '36%' },
    {
      title: t('fields.description'),
      dataIndex: 'description', align: 'left',
      render: (description: string | null) => description || <span style={{ color: 'var(--cf-muted)' }}>{t('environment.noDescription')}</span>,
    },
    ...(env.canManage ? ([{
      title: t('fields.actions'),
      width: 80,
      render: (_: unknown, variable: AgentEnvironmentVariableRef) => (
        <Popconfirm
          title={t('environment.confirmUnbind', { name: variable.name })}
          okText={t('actions.confirmUnbind')}
          cancelText={t('actions.cancel')}
          onConfirm={() => env.unmount(variable)}
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={t('environment.unbindLabel', { name: variable.name })}
            icon={<DeleteOutlined aria-hidden="true" />}
            disabled={env.pending}
          />
        </Popconfirm>
      ),
    }] as ColumnsType<AgentEnvironmentVariableRef>) : []),
  ];
  return (
    <div className="cfds-list-layout">

      {saveFeedback !== null && (
        <Alert
          showIcon
          type="success"
          message={t(saveFeedback ? 'edit.saved' : 'edit.draftSaved')}
          description={t(saveFeedback ? 'edit.savedHint' : 'edit.draftSavedHint')}
        />
      )}

      <AgentBasicsCard
        agentId={agentId}
        agent={agent}
        versionDetail={versionDetail}
        isPlatform={isPlatform}
        form={form}
        businessBackgroundRef={businessBackgroundRef}
        sdlcsList={sdlcsList}
        sdlcStatusLabels={sdlcStatusLabels}
        editConfigPending={editConfig.isPending}
        updateAgentPending={updateAgent.isPending}
        submitForReviewPending={submitForReview.isPending}
        serverOperationPending={env.serverOperationPending}
        onSave={handleSave}
        onSubmit={handleSubmit}
        onRoleNameSelect={handleRoleNameSelect}
        onRoleCodeSelect={handleRoleCodeSelect}
        onRoleCodeBlur={handleRoleCodeBlur}
        onRoleCodeKeyDown={handleRoleCodeKeyDown}
      />

      <AgentRepoAccessCard
        isPlatform={isPlatform}
        columns={repoColumns}
        dataSource={repoPerms}
        onAdd={() => accessCommand('READ_WRITE', t('actions.addRepo'), () => setRepoModalOpen(true))}
      />

      <AgentCapabilitiesCard
        columns={skillColumns}
        dataSource={skills}
        onAdd={() => accessCommand('READ_WRITE', t('actions.addCapability'), () => setSkillModalOpen(true))}
      />

      <AgentEnvironmentCard
        canManage={env.canManage}
        columns={environmentVariableColumns}
        dataSource={env.mounted}
        loading={isVersionDetailLoading}
        error={isVersionDetailError}
        fetching={isVersionDetailFetching}
        errorValue={versionDetailError}
        onRetry={() => refetchVersionDetail()}
        bindingError={env.bindingError}
        onDismissBindingError={env.clearBindingError}
        onMount={env.openMountModal}
        pending={env.pending}
        versionReady={env.versionReady}
      />

      {/* Add Repo Modal */}
      <Modal title={t('repository.addPermission')} open={repoModalOpen} onOk={handleAddRepo} onCancel={() => setRepoModalOpen(false)}
        okButtonProps={{ disabled: selectedRepoIds.length === 0 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select mode="multiple" placeholder={t('placeholders.repositories')} style={{ width: '100%' }} value={selectedRepoIds}
            onChange={setSelectedRepoIds} showSearch optionFilterProp="label"
            options={reposList.filter(r => !repoPerms.some(p => p.repoId === r.id))
              .map(r => ({ value: r.id, label: r.name })) || []}
          />
          <Select value={selectedPermLevel} onChange={setSelectedPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: t('permissions.READ') },
              { value: 'WRITE', label: t('permissions.WRITE') },
              { value: 'ADMIN', label: t('permissions.ADMIN') },
            ]}
          />
          <Select mode="tags" aria-label={t('fields.allowedBranches')} value={selectedBranchPatterns}
            onChange={setSelectedBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder={t('placeholders.branches')} maxCount={32}
          />
        </Space>
      </Modal>

      <Modal title={t('repository.editPermission')} open={editingRepoPerm !== null} onOk={handleEditRepoPerm}
        onCancel={() => setEditingRepoPerm(null)}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input value={editingRepoPerm?.repoName} disabled />
          <Select value={editingPermLevel} onChange={setEditingPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: t('permissions.READ') },
              { value: 'WRITE', label: t('permissions.WRITE') },
              { value: 'ADMIN', label: t('permissions.ADMIN') },
            ]}
          />
          <Select mode="tags" aria-label={t('fields.allowedBranches')} value={editingBranchPatterns}
            onChange={setEditingBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder={t('placeholders.editBranches')} maxCount={32}
          />
        </Space>
      </Modal>

      {/* Add Capability Modal */}
      <Modal title={t('capability.add')} open={skillModalOpen} onOk={handleAddSkill} onCancel={() => setSkillModalOpen(false)}
        okButtonProps={{ disabled: selectedSkillIds.length === 0 }}>
        <Select mode="multiple" placeholder={t('placeholders.capabilities')} style={{ width: '100%' }} value={selectedSkillIds}
          onChange={setSelectedSkillIds} showSearch optionFilterProp="label"
          options={skillsList.filter(s => !skills.some(sk => sk.skillId === s.id))
            .map(s => ({ value: s.id, label: t('namedLabel', { name: s.name, label: capabilityTypeLabels.get(s.type) || s.type }) })) || []}
        />
      </Modal>

      <Modal
        title={t('environment.mount')}
        open={env.modalOpen && env.canManage}
        okText={t('actions.mount')}
        cancelText={t('actions.cancel')}
        onOk={env.mount}
        onCancel={env.closeMountModal}
        confirmLoading={Boolean(env.pending && env.pendingKey?.startsWith('mount:'))}
        okButtonProps={{
          disabled: env.selectedVariableId === undefined
            || env.pending
            || !env.versionReady,
        }}
      >
        {env.mountError && (
          <Alert
            type="error"
            showIcon
            message={errorMessage(env.mountError.error, t('errors.mount'))}
            style={{ marginBottom: 12 }}
          />
        )}
        {!env.versionReady ? (
          <Alert type="warning" showIcon message={t('environment.waitVersion')} />
        ) : env.library.isError ? (
          <Alert type="error" showIcon message={t('errors.libraryLoad')} />
        ) : env.available.length === 0 && !env.library.isLoading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={(env.library.data?.length ?? 0) === 0
              ? t('environment.libraryEmpty')
              : t('environment.allMounted')}
          />
        ) : (
          <Select
            aria-label={t('fields.selectVariable')}
            showSearch
            allowClear
            loading={env.library.isLoading}
            placeholder={t('placeholders.variables')}
            optionFilterProp="label"
            style={{ width: '100%' }}
            value={env.selectedVariableId}
            onChange={env.setSelectedVariableId}
            options={env.available.map(variable => ({
              value: variable.id,
              label: `${variable.name}${variable.description ? ` — ${variable.description}` : ''}`,
            }))}
          />
        )}
      </Modal>
    </div>
  );
}
