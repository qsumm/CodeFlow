import { useEffect, useMemo, useRef, useState } from 'react';
import type { Key } from 'react';
import { message } from 'antd';
import { useTranslation } from 'react-i18next';
import { downloadSkillPackage, getSkillPackageFile, getSkillPackageFiles } from '../api';
import type { Skill, SkillPackageFile, SkillPackageFileContent } from '../api';
import { buildPackageTree } from '../skillPackage';
import { errorMessage, isPackageSkill } from '../skillFormat';

/**
 * 技能包清单/预览/下载的状态。
 * 目录树按需拉取（仅在打开详情时），单文件内容在选中时再取。
 */
export function useSkillPackage(detailSkill: Skill | null) {
  const { t } = useTranslation('skill');
  const [packageFiles, setPackageFiles] = useState<SkillPackageFile[]>([]);
  const [packageFilesLoading, setPackageFilesLoading] = useState(false);
  const [packageFilesError, setPackageFilesError] = useState<{ message?: string } | null>(null);
  const [selectedPackagePath, setSelectedPackagePath] = useState<string | null>(null);
  const [packageFileContent, setPackageFileContent] = useState<SkillPackageFileContent | null>(null);
  const [packageFileLoading, setPackageFileLoading] = useState(false);
  const [downloadingPackage, setDownloadingPackage] = useState(false);
  // 单文件内容请求的自增序号：用于作废在途响应，避免跨技能同名文件（如 SKILL.md）乱序返回时渲染错内容
  const packageFileRequestSeq = useRef(0);

  // 打开详情时才拉目录树。cancelled 标志防止快速切换技能时旧响应覆盖新技能的包内容。
  useEffect(() => {
    // 同时作废上一个技能仍在途的单文件内容请求，否则它返回后会写进新技能的预览面板
    packageFileRequestSeq.current += 1;
    setPackageFiles([]);
    setPackageFilesError(null);
    setSelectedPackagePath(null);
    setPackageFileContent(null);
    setPackageFileLoading(false);
    if (!isPackageSkill(detailSkill)) {
      setPackageFilesLoading(false);
      return;
    }
    let cancelled = false;
    setPackageFilesLoading(true);
    getSkillPackageFiles(detailSkill.id)
      .then((result) => {
        if (!cancelled) setPackageFiles(result.files ?? []);
      })
      .catch((error) => {
        if (!cancelled) setPackageFilesError(error instanceof Error && error.message ? { message: error.message } : {});
      })
      .finally(() => {
        if (!cancelled) setPackageFilesLoading(false);
      });
    return () => { cancelled = true; };
  }, [detailSkill]);

  const packageEntryMap = useMemo(
    () => new Map(packageFiles.map((file) => [file.path, file])),
    [packageFiles],
  );
  const packageTree = useMemo(() => buildPackageTree(packageFiles), [packageFiles, t]);
  const selectedPackageEntry = selectedPackagePath ? packageEntryMap.get(selectedPackagePath) ?? null : null;

  const handlePackageSelect = async (keys: Key[]) => {
    const path = keys.length > 0 ? String(keys[0]) : null;
    // 取号即作废此前所有在途请求：先发出但后返回的响应不得覆盖当前选中项
    const seq = ++packageFileRequestSeq.current;
    const stale = () => seq !== packageFileRequestSeq.current;
    setSelectedPackagePath(path);
    setPackageFileContent(null);
    setPackageFileLoading(false);
    if (!path || !detailSkill) return;
    const entry = packageEntryMap.get(path);
    // 目录只展开层级；图片与二进制只展示元信息，不请求内容
    if (!entry || entry.dir || entry.kind !== 'TEXT') return;
    setPackageFileLoading(true);
    try {
      const content = await getSkillPackageFile(detailSkill.id, path);
      if (!stale()) setPackageFileContent(content);
    } catch (error) {
      if (!stale()) message.error(errorMessage(error));
    } finally {
      if (!stale()) setPackageFileLoading(false);
    }
  };

  const handleDownloadPackage = async () => {
    if (!detailSkill) return;
    setDownloadingPackage(true);
    try {
      await downloadSkillPackage(detailSkill.id, detailSkill.packageFileName);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setDownloadingPackage(false);
    }
  };

  /** 关闭详情时清空预览面板，避免下次打开残留上一次的文件正文。 */
  const resetPackagePreview = () => {
    setPackageFiles([]);
    setPackageFilesError(null);
    setSelectedPackagePath(null);
    setPackageFileContent(null);
  };

  return {
    packageFiles,
    packageFilesLoading,
    packageFilesError,
    packageTree,
    selectedPackagePath,
    selectedPackageEntry,
    packageFileContent,
    packageFileLoading,
    downloadingPackage,
    handlePackageSelect,
    handleDownloadPackage,
    resetPackagePreview,
  };
}
