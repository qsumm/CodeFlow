import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ApiError } from '@/shared/types/common';
import { ImIdentityConflictAlert, workspaceChannelsUrl } from './ImIdentityConflictAlert';

function conflictError(data: unknown) {
  return new ApiError('10409', 'conflict', null, data);
}

describe('ImIdentityConflictAlert', () => {
  it('renders the required legacy-occupancy copy with visible workspace links', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [{ workspaceId: 7, workspaceName: '项目A' }],
      restricted: false,
    })} />);

    expect(screen.getByText('这个机器人身份已被别的项目占用,先去原项目删掉群机器人配置')).toBeInTheDocument();
    expect(screen.getByText('占用该配置的项目：')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /项目A/ });
    expect(link).toHaveAttribute('href', '/workspaces?targetWorkspaceId=7&returnTo=%2Fintegrations%3Ftab%3Dchannels');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('删原绑定会中断它的群聊服务。')).toBeInTheDocument();
  });

  it('marks unreadable occupants as restricted without exposing them', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [],
      restricted: true,
    })} />);

    expect(screen.getByText('占用方是你无权查看的项目,联系那个项目的管理员处理。')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('explains a deleted or unavailable occupying workspace', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [],
      restricted: false,
    })} />);

    expect(screen.getByText('占用该项目已删除或入口失效,联系对应管理员处理。')).toBeInTheDocument();
  });

  it('separates notification/group-chat conflicts from legacy occupancy', () => {
    const { rerender } = render(<ImIdentityConflictAlert error={conflictError({
      reason: 'NOTIFY_GROUP_IDENTITY_CONFLICT',
      workspaces: [],
      restricted: false,
    })} />);
    expect(screen.getByText('通知机器人与群机器人必须用不同的应用身份。')).toBeInTheDocument();
    expect(screen.queryByText('删原绑定会中断它的群聊服务。')).not.toBeInTheDocument();

    rerender(<ImIdentityConflictAlert error={conflictError({
      reason: 'SHARED_IDENTITY_OCCUPIED',
      workspaces: [],
      restricted: false,
    })} />);
    expect(screen.getByText('该应用身份已被平台群机器人占用,换一个。')).toBeInTheDocument();
  });

  it('renders nothing for errors without a structured reason', () => {
    const { container } = render(<ImIdentityConflictAlert error={new Error('plain failure')} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('builds a workspace-scoped deep link that performs the switch on the workspace page', () => {
    expect(workspaceChannelsUrl(42)).toBe(
      '/workspaces?targetWorkspaceId=42&returnTo=%2Fintegrations%3Ftab%3Dchannels',
    );
  });
});
