import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Card, Space, Tooltip, Typography } from 'antd';
import { CompressOutlined, FullscreenOutlined, ReloadOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { formatDuration } from '../duration';
import { graphAgentName, runtimeEdgeLabel, runtimeStatusLabel } from '../runtimeLabels';
import type { Artifact, DispatchAttempt, ProcessGraph, ProcessGraphEdge, ProcessGraphNode } from '@/shared/types/workitem';
import { RuntimeTraceDrawer } from './RuntimeTraceDrawer';
import type { ConvergedAgentProgress } from './DeliveryProgress';

const { Text } = Typography;

type GraphSelection = { node: ProcessGraphNode } | { edge: ProcessGraphEdge; index: number; source: ProcessGraphNode; target: ProcessGraphNode };

function artifactDispatchIds(agent: ConvergedAgentProgress): Set<number> {
  const ids = new Set<number>();
  agent.steps.forEach((step) => {
    step.attempts?.forEach((attempt) => {
      if (attempt.dispatchId != null) {
        ids.add(Number(attempt.dispatchId));
      }
    });
  });
  return ids;
}

// Distinct dispatch ids for an agent in step/attempt order (oldest first), so retries and
// multi-dispatch history stay selectable instead of only exposing the latest one.
export function orderedDispatchIds(agent: ConvergedAgentProgress): number[] {
  return Array.from(artifactDispatchIds(agent));
}

export function artifactsForAgent(agent: ConvergedAgentProgress, artifacts: Artifact[], executions?: DispatchAttempt[] | null): Artifact[] {
  const dispatchIds = artifactDispatchIds(agent);
  executions?.filter(execution => execution.agentId === agent.agentId).forEach(execution => dispatchIds.add(execution.dispatchId));
  return artifacts.filter((artifact) => artifact.dispatchId != null && dispatchIds.has(Number(artifact.dispatchId)));
}

function clampZoom(value: number): number {
  return Math.min(2.4, Math.max(0.45, Number(value.toFixed(2))));
}

function markerDefs() {
  return <defs>
    <marker id="execution-graph-arrow-pass" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--cf-info)" /></marker>
    <marker id="execution-graph-arrow-reject" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--cf-error)" /></marker>
    <marker id="execution-graph-arrow-pause" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--cf-warning)" /></marker>
  </defs>;
}

function authoritativeNodeTone(status?: string | null) {
  const value = status?.toUpperCase();
  if (value === 'RUNNING' || value === 'ACKED' || value === 'DISPATCHED') return { border: 'var(--cf-accent-text)', background: 'rgba(var(--cf-accent-rgb),.10)' };
  if (value === 'FAILED' || value === 'TIMEOUT' || value === 'CANCELED') return { border: 'var(--cf-error)', background: 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))' };
  if (value?.includes('PAUS')) return { border: 'var(--cf-warning)', background: 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))' };
  if (value === 'SUCCEEDED' || value === 'HUMAN') return { border: 'var(--cf-success)', background: 'color-mix(in srgb, var(--cf-success) 10%, var(--cf-panel))' };
  return { border: 'var(--cf-border)', background: 'var(--cf-raised)' };
}

function authoritativeEdgeTone(type: ProcessGraphEdge['type']) {
  if (type === 'COMMENT_REWORK') return { color: 'var(--cf-error)', background: 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-error) 35%, var(--cf-border))', markerId: 'execution-graph-arrow-reject' };
  if (type === 'CONTINUE') return { color: 'var(--cf-warning)', background: 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-warning) 35%, var(--cf-border))', markerId: 'execution-graph-arrow-pause' };
  return { color: 'var(--cf-info)', background: 'color-mix(in srgb, var(--cf-info) 10%, var(--cf-panel))', border: 'var(--cf-info)', markerId: 'execution-graph-arrow-pass' };
}

export function AuthoritativeExecutionProcessGraph({ processGraph }: { processGraph?: ProcessGraph | null }) {
  const { t } = useTranslation('workitem');
  const dragState = useRef<{ active: boolean; startX: number; startY: number; panX: number; panY: number }>({ active: false, startX: 0, startY: 0, panX: 0, panY: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [fullscreen, setFullscreen] = useState(false);
  const [selected, setSelection] = useState<GraphSelection | null>(null);
  const selection = selected && ('node' in selected ? {
    title: graphAgentName(selected.node), status: runtimeStatusLabel(selected.node.status),
    duration: formatDuration(selected.node.durationMs) || t('delivery.none'),
    detail: `${selected.node.stepName || t('delivery.humanHandoff')}${selected.node.dispatchId != null ? ` · dispatch ${selected.node.dispatchId}` : ''}${selected.node.error ? ` · ${selected.node.error}` : ''}`,
  } : {
    title: t('delivery.transition', { number: selected.index + 1 }), status: runtimeEdgeLabel(selected.edge, selected.source.status), duration: t('delivery.lineage'),
    detail: `${graphAgentName(selected.source)} -> ${graphAgentName(selected.target)} · dispatch ${selected.edge.sourceDispatchId ?? '-'} -> ${selected.edge.targetDispatchId ?? '-'}`,
  });
  const [traceNode, setTraceNode] = useState<ProcessGraphNode | null>(null);

  if (!processGraph || processGraph.nodes.length === 0) return null;

  const nodesByKey = new Map(processGraph.nodes.map((node) => [node.key, node]));
  const laneKey = (node: ProcessGraphNode) => node.agentId != null ? `agent:${node.agentId}` : node.key;
  const lanes = Array.from(new Set(processGraph.nodes.map(laneKey)));
  const laneSpacing = 220;
  const rowSpacing = 112;
  const width = Math.max(680, 150 + Math.max(0, lanes.length - 1) * laneSpacing + 150);
  const height = Math.max(340, 80 + Math.max(0, processGraph.nodes.length - 1) * rowSpacing + 100);
  const hasGraph = processGraph.nodes.length > 0;
  const positions = new Map(processGraph.nodes.map((node, index) => [node.key, {
    x: 150 + lanes.indexOf(laneKey(node)) * laneSpacing,
    y: 70 + index * rowSpacing,
  }]));

  const zoomBy = (delta: number) => setZoom((value) => clampZoom(value + delta));
  const resetView = () => { setZoom(1); setPan({ x: 0, y: 0 }); };
  const beginDrag = (x: number, y: number) => { dragState.current = { active: true, startX: x, startY: y, panX: pan.x, panY: pan.y }; };
  const moveDrag = (x: number, y: number) => {
    if (!dragState.current.active) return;
    setPan({ x: dragState.current.panX + x - dragState.current.startX, y: dragState.current.panY + y - dragState.current.startY });
  };
  const endDrag = () => { dragState.current.active = false; };

  // 无执行过程数据时不渲染 420px 的大空盒子(视觉评审:大片空白+蓝色边框)
  if (!hasGraph) {
    return (
      <Card size="small" title={t('delivery.graph')} data-testid="execution-process-graph">
        <div style={{ height: 120, display: 'grid', placeItems: 'center', color: 'var(--cf-muted)', fontSize: 12 }}>
          {t('delivery.graphEmpty')}
        </div>
      </Card>
    );
  }
  const graph = (
    <Card
      size="small"
      title={t('delivery.graph')}
      data-testid="execution-process-graph"
      extra={<Space size={4}>
        <Tooltip title={t('delivery.zoomOut')}><Button size="small" aria-label={t('delivery.zoomOut')} icon={<ZoomOutOutlined aria-hidden="true" />} onClick={() => zoomBy(-0.12)} /></Tooltip>
        <Tooltip title={t('delivery.zoomIn')}><Button size="small" aria-label={t('delivery.zoomIn')} icon={<ZoomInOutlined aria-hidden="true" />} onClick={() => zoomBy(0.12)} /></Tooltip>
        <Tooltip title={t('delivery.resetView')}><Button size="small" aria-label={t('delivery.resetView')} icon={<ReloadOutlined aria-hidden="true" />} onClick={resetView} /></Tooltip>
        <Tooltip title={fullscreen ? t('delivery.exitFullscreen') : t('delivery.fullscreen')}>
          <Button size="small" aria-label={fullscreen ? t('delivery.exitFullscreen') : t('delivery.fullscreen')} icon={fullscreen ? <CompressOutlined aria-hidden="true" /> : <FullscreenOutlined aria-hidden="true" />} onClick={() => setFullscreen((value) => !value)} />
        </Tooltip>
      </Space>}
      styles={{ body: { padding: '8px 10px' } }}
    >
      <div
        data-testid="execution-graph-viewport"
        data-zoom={zoom.toFixed(2)}
        data-pan={`${Math.round(pan.x)},${Math.round(pan.y)}`}
        onWheel={(event) => { event.preventDefault(); zoomBy(event.deltaY > 0 ? -0.08 : 0.08); }}
        onMouseDown={(event) => beginDrag(event.clientX, event.clientY)}
        onMouseMove={(event) => moveDrag(event.clientX, event.clientY)}
        onMouseUp={endDrag}
        onMouseLeave={endDrag}
        style={{ height: fullscreen ? 'calc(100vh - 190px)' : 420, border: '1px solid var(--cf-border)', borderRadius: 6, background: 'var(--cf-panel)', overflow: 'hidden', cursor: 'grab' }}
      >
        <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={t('delivery.graphAria')}>
          {markerDefs()}
          <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
            {lanes.map((lane, index) => <line key={lane} x1={150 + index * laneSpacing} y1="24" x2={150 + index * laneSpacing} y2={height - 24} stroke="var(--cf-border)" strokeWidth="2" strokeDasharray="5 5" />)}
            {processGraph.edges.map((edge, index) => {
              const source = nodesByKey.get(edge.sourceKey);
              const target = nodesByKey.get(edge.targetKey);
              const from = positions.get(edge.sourceKey);
              const to = positions.get(edge.targetKey);
              if (!source || !target || !from || !to) return null;
              const tone = authoritativeEdgeTone(edge.type);
              const sameLane = from.x === to.x;
              const path = sameLane
                ? `M ${from.x + 66} ${from.y + 24} C ${from.x + 136} ${from.y + 44}, ${to.x + 136} ${to.y - 44}, ${to.x + 66} ${to.y - 24}`
                : `M ${from.x} ${from.y + 31} C ${from.x} ${from.y + 64}, ${to.x} ${to.y - 64}, ${to.x} ${to.y - 31}`;
              const labelX = sameLane ? from.x + 138 : (from.x + to.x) / 2;
              const labelY = (from.y + to.y) / 2;
              return <Tooltip key={`${edge.sourceKey}-${edge.targetKey}-${index}`} title={t('delivery.edgeSummary', { label: runtimeEdgeLabel(edge, source.status), source: edge.sourceDispatchId ?? '-', target: edge.targetDispatchId ?? '-' })}>
                <g data-testid="execution-graph-edge" role="button" tabIndex={0} aria-label={t('delivery.edgeAria', { number: index + 1, source: graphAgentName(source), target: graphAgentName(target), label: runtimeEdgeLabel(edge, source.status) })} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ edge, index, source, target });
                }} style={{ cursor: 'pointer' }}>
                  <path d={path} fill="none" stroke={tone.color} strokeWidth="2" markerEnd={`url(#${tone.markerId})`} vectorEffect="non-scaling-stroke" />
                  <rect x={labelX - 104} y={labelY - 12} width="208" height="24" rx="5" fill={tone.background} stroke={tone.border} />
                  <text x={labelX} y={labelY + 4} textAnchor="middle" style={{ fontSize: 11, fill: tone.color, fontWeight: 600 }}>{t('delivery.edgeText', { number: index + 1, source: graphAgentName(source), target: graphAgentName(target), label: runtimeEdgeLabel(edge, source.status) })}</text>
                </g>
              </Tooltip>;
            })}
            {processGraph.nodes.map((node) => {
              const position = positions.get(node.key)!;
              const tone = authoritativeNodeTone(node.status);
              return <Tooltip key={node.key} title={t('delivery.nodeSummary', { name: graphAgentName(node), step: node.stepName || t('delivery.humanHandoff'), status: runtimeStatusLabel(node.status) })}>
                <g data-testid="execution-graph-worker" data-status={node.status || ''} role="button" tabIndex={0} aria-label={t('delivery.nodeAria', { name: graphAgentName(node), status: runtimeStatusLabel(node.status) })} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ node });
                  if (node.dispatchId != null) setTraceNode(node);
                }} style={{ cursor: 'pointer' }}>
                  <rect x={position.x - 66} y={position.y - 31} width="132" height="62" rx="7" fill={tone.background} stroke={tone.border} strokeWidth="1.4" />
                  <text x={position.x} y={position.y - 9} textAnchor="middle" style={{ fontSize: 13, fontWeight: 600, fill: 'var(--cf-text)' }}>{graphAgentName(node)}</text>
                  <text x={position.x} y={position.y + 9} textAnchor="middle" style={{ fontSize: 11, fill: 'var(--cf-muted)' }}>{node.stepName || t('delivery.humanHandoff')}</text>
                  <text x={position.x} y={position.y + 24} textAnchor="middle" style={{ fontSize: 10, fill: 'var(--cf-muted)' }}>{node.dispatchId != null ? `#${node.dispatchId}` : ''} {formatDuration(node.durationMs)}</text>
                </g>
              </Tooltip>;
            })}
          </g>
        </svg>
      </div>
      <div data-testid="execution-graph-detail-panel" style={{ marginTop: 8, minHeight: 58, padding: '7px 9px', border: '1px solid var(--cf-border)', borderRadius: 6, background: 'var(--cf-raised)' }}>
        {selection ? <Space direction="vertical" size={1}><Text strong style={{ fontSize: 12 }}>{selection.title}</Text><Text type="secondary" style={{ fontSize: 12 }}>{t('delivery.selection', { status: selection.status, duration: selection.duration })}</Text><Text type="secondary" style={{ fontSize: 12 }}>{selection.detail}</Text></Space> : <Text type="secondary" style={{ fontSize: 12 }}>{t('delivery.graphHint')}</Text>}
      </div>
      <RuntimeTraceDrawer node={traceNode} processGraph={processGraph} onClose={() => setTraceNode(null)} />
    </Card>
  );

  return fullscreen ? <div data-testid="execution-graph-fullscreen" style={{ position: 'fixed', inset: 0, zIndex: 1000, padding: 16, background: 'var(--cf-panel)' }}>{graph}</div> : graph;
}

