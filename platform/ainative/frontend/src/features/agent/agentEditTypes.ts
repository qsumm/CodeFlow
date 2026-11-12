/** 编辑页关系表行的展示模型(前后端字段不完全一致,单独收口)。 */
export interface RepoPermRow {
  repoId: number;
  repoName: string;
  permLevel: string;
  allowedBranchPatterns: string[];
}

export interface SkillRow {
  skillId: number;
  skillName: string;
  type: 'SKILL' | 'MCP' | 'PLUGIN' | 'HOOK';
  version?: number;
}
