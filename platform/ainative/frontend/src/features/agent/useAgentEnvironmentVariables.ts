import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAuthStore } from '@/shared/auth/store';
import { environmentVariablesQueryKey, useEnvironmentVariables } from '@/features/environmentVariables/hooks';
import type { WorkspaceAccessLevel } from '@/shared/types/common';
import { useAddEnvironmentVariableRef, useRemoveEnvironmentVariableRef } from './hooks';
import type { AgentEnvironmentVariableRef, AgentVersion } from './api';

function uniqueBy<T>(items: T[], keyOf: (item: T) => string | number): T[] {
  return [...new Map(items.map(item => [keyOf(item), item])).values()];
}

/**
 * 编辑页环境变量绑定:挂载/卸载 + 工作区·权限边界隔离(ABA 安全)。
 * 页面只拿状态与动作,不关心 token/generation 细节。
 */
export function useAgentEnvironmentVariables({
  agentId,
  versionDetail,
  versionLoading,
  versionError,
  latestVersionNo,
}: {
  agentId: number;
  versionDetail: AgentVersion | undefined;
  versionLoading: boolean;
  versionError: boolean;
  latestVersionNo: number;
}) {
  const { t } = useTranslation('agent');
  const accessCommand = useAccessCommand();
  const queryClient = useQueryClient();
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  const accessLevel = useAuthStore((state) => state.accessLevel);
  const canManageEnvironmentVariables = useAuthStore((state) => state.hasAccess('READ_WRITE'));
  const addEnvironmentVariableRef = useAddEnvironmentVariableRef();
  const removeEnvironmentVariableRef = useRemoveEnvironmentVariableRef();
  const environmentVariableLibrary = useEnvironmentVariables(canManageEnvironmentVariables);

interface EnvironmentBindingOperation {
  context: string;
  generation: number;
  key: string;
  token: number;
}

interface EnvironmentBindingBoundary {
  workspaceId: number | undefined;
  agentId: number;
  accessLevel: WorkspaceAccessLevel | null;
  context: string;
  generation: number;
}

function environmentBindingContext(
  workspaceId: number | undefined,
  agentId: number,
  accessLevel: WorkspaceAccessLevel | null,
) {
  return `${workspaceId ?? 'none'}:${agentId}:${accessLevel ?? 'none'}`;
}

function environmentBindingServerScope(workspaceId: number | undefined, agentId: number) {
  return `${workspaceId ?? 'none'}:${agentId}`;
}

  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false);
  const [selectedEnvironmentVariableId, setSelectedEnvironmentVariableId] = useState<number>();
  const [environmentBindingError, setEnvironmentBindingError] = useState<{ error: unknown }>();
  const [environmentMountError, setEnvironmentMountError] = useState<{ error: unknown }>();
  const [pendingEnvironmentOperation, setPendingEnvironmentOperation] = useState<EnvironmentBindingOperation>();
  const environmentOperationTokens = useRef(new Map<string, number>());
  const nextEnvironmentOperationToken = useRef(0);
  const pendingEnvironmentServerOperations = useRef(new Map<string, Map<number, string>>());
  const [, setEnvironmentServerOperationRevision] = useState(0);
  const [, setEnvironmentBoundaryRevision] = useState(0);
  const environmentBoundaryRef = useRef<EnvironmentBindingBoundary>({
    workspaceId,
    agentId,
    accessLevel,
    context: environmentBindingContext(workspaceId, agentId, accessLevel),
    generation: 0,
  });
  if (environmentBoundaryRef.current.agentId !== agentId
      || environmentBoundaryRef.current.workspaceId !== workspaceId
      || environmentBoundaryRef.current.accessLevel !== accessLevel) {
    const previousGeneration = environmentBoundaryRef.current.generation;
    environmentBoundaryRef.current = {
      workspaceId,
      agentId,
      accessLevel,
      context: environmentBindingContext(workspaceId, agentId, accessLevel),
      generation: previousGeneration + 1,
    };
    environmentOperationTokens.current.clear();
  }
  const environmentBoundaryGeneration = environmentBoundaryRef.current.generation;
  const environmentContext = environmentBoundaryRef.current.context;
  const currentEnvironmentServerScope = environmentBindingServerScope(workspaceId, agentId);
  const environmentServerOperationPending = (pendingEnvironmentServerOperations.current
    .get(currentEnvironmentServerScope)?.size ?? 0) > 0;
  const environmentOperationPending = pendingEnvironmentOperation?.context === environmentContext
    && pendingEnvironmentOperation.generation === environmentBoundaryGeneration;

  const isEnvironmentOperationCurrent = (operation: EnvironmentBindingOperation) => {
    const boundary = environmentBoundaryRef.current;
    return boundary.context === operation.context
      && boundary.generation === operation.generation
      && environmentOperationTokens.current.get(operation.key) === operation.token;
  };
  const hasPendingEnvironmentServerOperation = () =>
    (pendingEnvironmentServerOperations.current
      .get(environmentBindingServerScope(
        useAuthStore.getState().currentWorkspace?.id,
        agentId,
      ))?.size ?? 0) > 0;

  const mountedEnvironmentVariables = useMemo(
    () => uniqueBy(versionDetail?.environmentVariables ?? [], variable => variable.id),
    [versionDetail],
  );
  const availableEnvironmentVariables = useMemo(() => {
    const mountedIds = new Set(mountedEnvironmentVariables.map(variable => variable.id));
    return (environmentVariableLibrary.data ?? []).filter(variable => !mountedIds.has(variable.id));
  }, [environmentVariableLibrary.data, mountedEnvironmentVariables]);

  const environmentVersionReady = Boolean(versionDetail)
    && !versionLoading
    && !versionError;

  useEffect(() => useAuthStore.subscribe((state, previous) => {
    const nextWorkspaceId = state.currentWorkspace?.id;
    const previousWorkspaceId = previous.currentWorkspace?.id;
    if (nextWorkspaceId === previousWorkspaceId && state.accessLevel === previous.accessLevel) return;
    const current = environmentBoundaryRef.current;
    environmentBoundaryRef.current = {
      workspaceId: nextWorkspaceId,
      agentId: current.agentId,
      accessLevel: state.accessLevel,
      context: environmentBindingContext(nextWorkspaceId, current.agentId, state.accessLevel),
      generation: current.generation + 1,
    };
    environmentOperationTokens.current.clear();
    setEnvironmentModalOpen(false);
    setSelectedEnvironmentVariableId(undefined);
    setEnvironmentBindingError(undefined);
    setEnvironmentMountError(undefined);
    setPendingEnvironmentOperation(undefined);
    setEnvironmentBoundaryRevision(revision => revision + 1);
  }), []);

  useEffect(() => {
    setEnvironmentModalOpen(false);
    setSelectedEnvironmentVariableId(undefined);
    setEnvironmentBindingError(undefined);
    setEnvironmentMountError(undefined);
    setPendingEnvironmentOperation(undefined);
    environmentOperationTokens.current.clear();
  }, [environmentBoundaryGeneration]);

  const handleAddEnvironmentVariable = () => {
    if (!environmentVersionReady || !selectedEnvironmentVariableId || environmentOperationPending) return;
    const environmentVariableId = selectedEnvironmentVariableId;
    const operationKey = `mount:${environmentVariableId}`;
    if (environmentOperationTokens.current.has(operationKey)) return;
    accessCommand('READ_WRITE', t('actions.mountVariable'), async () => {
      const auth = useAuthStore.getState();
      const operationWorkspaceId = auth.currentWorkspace?.id;
      const operationAccessLevel = auth.accessLevel;
      const operationContext = environmentBindingContext(operationWorkspaceId, agentId, operationAccessLevel);
      const operationGeneration = environmentBoundaryRef.current.generation;
      if (!operationWorkspaceId || operationContext !== environmentBoundaryRef.current.context) return;
      const operationToken = ++nextEnvironmentOperationToken.current;
      const operation = {
        context: operationContext,
        generation: operationGeneration,
        key: operationKey,
        token: operationToken,
      };
      environmentOperationTokens.current.set(operationKey, operationToken);
      const operationServerScope = environmentBindingServerScope(operationWorkspaceId, agentId);
      const scopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope)
        ?? new Map<number, string>();
      scopeOperations.set(operationToken, operationKey);
      pendingEnvironmentServerOperations.current.set(operationServerScope, scopeOperations);
      setEnvironmentServerOperationRevision(revision => revision + 1);
      setPendingEnvironmentOperation(operation);
      setEnvironmentMountError(undefined);
      let operationFailed = false;
      try {
        await addEnvironmentVariableRef.mutateAsync({
          agentId,
          environmentVariableId,
        });
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'detail', operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'version', latestVersionNo, operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: environmentVariablesQueryKey(operationWorkspaceId),
            exact: true,
          }),
        ]);
        if (isEnvironmentOperationCurrent(operation)) {
          environmentOperationTokens.current.delete(`unmount:${environmentVariableId}`);
          setEnvironmentModalOpen(false);
          setSelectedEnvironmentVariableId(undefined);
          setEnvironmentMountError(undefined);
        }
      } catch (e) {
        operationFailed = true;
        if (isEnvironmentOperationCurrent(operation)) {
          setEnvironmentMountError({ error: e });
        }
      } finally {
        if (isEnvironmentOperationCurrent(operation)) {
          setPendingEnvironmentOperation(previous => previous?.token === operationToken
            && previous.context === operationContext
            && previous.generation === operationGeneration ? undefined : previous);
          if (operationFailed) {
            environmentOperationTokens.current.delete(operationKey);
          }
        }
        const pendingScopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope);
        pendingScopeOperations?.delete(operationToken);
        if (pendingScopeOperations?.size === 0) {
          pendingEnvironmentServerOperations.current.delete(operationServerScope);
        }
        setEnvironmentServerOperationRevision(revision => revision + 1);
      }
    });
  };

  const handleRemoveEnvironmentVariable = (variable: AgentEnvironmentVariableRef) => {
    if (!environmentVersionReady || environmentOperationPending) return;
    const operationKey = `unmount:${variable.id}`;
    if (environmentOperationTokens.current.has(operationKey)) return;
    accessCommand('READ_WRITE', t('actions.unmountVariable'), async () => {
      const auth = useAuthStore.getState();
      const operationWorkspaceId = auth.currentWorkspace?.id;
      const operationAccessLevel = auth.accessLevel;
      const operationContext = environmentBindingContext(operationWorkspaceId, agentId, operationAccessLevel);
      const operationGeneration = environmentBoundaryRef.current.generation;
      if (!operationWorkspaceId || operationContext !== environmentBoundaryRef.current.context) return;
      const operationToken = ++nextEnvironmentOperationToken.current;
      const operation = {
        context: operationContext,
        generation: operationGeneration,
        key: operationKey,
        token: operationToken,
      };
      environmentOperationTokens.current.set(operationKey, operationToken);
      const operationServerScope = environmentBindingServerScope(operationWorkspaceId, agentId);
      const scopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope)
        ?? new Map<number, string>();
      scopeOperations.set(operationToken, operationKey);
      pendingEnvironmentServerOperations.current.set(operationServerScope, scopeOperations);
      setEnvironmentServerOperationRevision(revision => revision + 1);
      setPendingEnvironmentOperation(operation);
      setEnvironmentBindingError(undefined);
      let operationFailed = false;
      try {
        await removeEnvironmentVariableRef.mutateAsync({
          agentId,
          environmentVariableId: variable.id,
        });
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'detail', operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'version', latestVersionNo, operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: environmentVariablesQueryKey(operationWorkspaceId),
            exact: true,
          }),
        ]);
        if (isEnvironmentOperationCurrent(operation)) {
          environmentOperationTokens.current.delete(`mount:${variable.id}`);
        }
      } catch (e) {
        operationFailed = true;
        if (isEnvironmentOperationCurrent(operation)) {
          setEnvironmentBindingError({ error: e });
        }
      } finally {
        if (isEnvironmentOperationCurrent(operation)) {
          setPendingEnvironmentOperation(previous => previous?.token === operationToken
            && previous.context === operationContext
            && previous.generation === operationGeneration ? undefined : previous);
          if (operationFailed) {
            environmentOperationTokens.current.delete(operationKey);
          }
        }
        const pendingScopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope);
        pendingScopeOperations?.delete(operationToken);
        if (pendingScopeOperations?.size === 0) {
          pendingEnvironmentServerOperations.current.delete(operationServerScope);
        }
        setEnvironmentServerOperationRevision(revision => revision + 1);
      }
    });
  };

  const openMountModal = () => {
    environmentOperationTokens.current.clear();
    setEnvironmentBindingError(undefined);
    setEnvironmentMountError(undefined);
    setEnvironmentModalOpen(true);
  };

  const closeMountModal = () => {
    setEnvironmentModalOpen(false);
    setSelectedEnvironmentVariableId(undefined);
    setEnvironmentMountError(undefined);
  };

  return {
    canManage: canManageEnvironmentVariables,
    mounted: mountedEnvironmentVariables,
    available: availableEnvironmentVariables,
    library: environmentVariableLibrary,
    modalOpen: environmentModalOpen,
    openMountModal,
    closeMountModal,
    selectedVariableId: selectedEnvironmentVariableId,
    setSelectedVariableId: setSelectedEnvironmentVariableId,
    bindingError: environmentBindingError,
    clearBindingError: () => setEnvironmentBindingError(undefined),
    mountError: environmentMountError,
    clearMountError: () => setEnvironmentMountError(undefined),
    pending: environmentOperationPending,
    pendingKey: pendingEnvironmentOperation?.key,
    serverOperationPending: environmentServerOperationPending,
    versionReady: environmentVersionReady,
    hasPendingServerOperation: hasPendingEnvironmentServerOperation,
    mount: handleAddEnvironmentVariable,
    unmount: handleRemoveEnvironmentVariable,
  };
}
