import { apiClient } from '@/shared/api/client';

export interface ZentaoBindingRequest {
  baseUrl: string;
  account: string;
  password?: string;
  externalProjectId?: string;
  externalProjectName?: string | null;
  pollIntervalSeconds?: number;
  enabled?: boolean;
}

export interface ZentaoTestResult {
  success: boolean;
  message: string;
}

export interface ZentaoBinding {
  id: number;
  provider: string;
  externalProjectId: string;
  externalProjectName: string | null;
  baseUrl: string;
  clientKey: string;
  enabled: number;
  lastError: string | null;
}

export interface ZentaoProject {
  externalId: string;
  name: string | null;
}

export async function testZentaoConnection(data: ZentaoBindingRequest): Promise<ZentaoTestResult> {
  const resp = await apiClient.post<{ success: boolean; message: string }>(
    '/api/integrations/zentao/bindings/test', data);
  return resp.data;
}

export async function searchZentaoProjects(data: ZentaoBindingRequest): Promise<ZentaoProject[]> {
  const resp = await apiClient.post<ZentaoProject[]>('/api/integrations/zentao/projects/search', data);
  return resp.data;
}

export async function createZentaoBinding(data: ZentaoBindingRequest): Promise<ZentaoBinding> {
  const resp = await apiClient.post<ZentaoBinding>('/api/integrations/zentao/bindings', data);
  return resp.data;
}

export async function listZentaoBindings(): Promise<ZentaoBinding[]> {
  const resp = await apiClient.get<ZentaoBinding[]>('/api/integrations/zentao/bindings');
  return resp.data;
}
