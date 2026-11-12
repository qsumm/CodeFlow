import type { AgentDeliveryProgress, DeliveryStep, SubStep } from '@/shared/types/workitem';

export type StepDisplayStatus = DeliveryStep['status'] | 'cancelled' | 'interrupted';

export type ConvergedDeliveryStep = Omit<DeliveryStep, 'status' | 'subSteps'> & {
  status: StepDisplayStatus;
  subSteps: (Omit<SubStep, 'status'> & { status: SubStep['status'] | 'cancelled' })[] | null;
};

export type ConvergedAgentProgress = Omit<AgentDeliveryProgress, 'steps'> & {
  steps: ConvergedDeliveryStep[];
};
