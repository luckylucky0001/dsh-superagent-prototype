import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Background, Controls, MarkerType, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { Button, Modal, Space, Spin, Tag, Typography } from 'antd';
import { RollbackOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { useComponents } from '@/api/queries';
import { useT } from '@/app/i18n';
import { fmtDateTime } from '@/components/format';
import { nodeTypes } from './FlowNode';
import { defToFlow, type FlowEdge, type FlowNode } from './graph';

/** An old workflow version on a read-only canvas, with a button to load it into the editor. */
export function VersionPreview({ workflowId, version, current, onClose, onRestore }: {
  workflowId: string; version: number | null; current: number; onClose: () => void; onRestore: (version: number) => void;
}) {
  const t = useT();
  const components = useComponents();
  const detail = useQuery({
    queryKey: ['workflow', workflowId, 'version', version],
    queryFn: () => api.workflowVersion(workflowId, version!),
    enabled: version !== null,
    staleTime: Infinity,
  });
  const flow = useMemo(() => {
    if (!detail.data || !components.data) return null;
    const descriptors = new Map(components.data.map((c) => [c.type, c]));
    const f = defToFlow(detail.data.definition, descriptors, true);
    return {
      nodes: f.nodes,
      edges: f.edges.map((e) => ({
        ...e,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: '#8c99ad' },
        style: { stroke: e.sourceHandle === 'error' ? '#ff7875' : '#8c99ad', strokeDasharray: e.sourceHandle === 'error' ? '5 4' : undefined },
        labelStyle: { fontSize: 11, fill: '#595959' },
      })),
    };
  }, [detail.data, components.data]);

  return (
    <Modal
      open={version !== null}
      onCancel={onClose}
      width="80vw"
      title={
        <Space>
          {t('wf.versionTitle', { v: version ?? '' })}
          {version === current && <Tag color="blue" bordered={false}>{t('wf.currentVersion')}</Tag>}
          {detail.data?.createdAt && <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>{fmtDateTime(detail.data.createdAt)}</Typography.Text>}
        </Space>
      }
      footer={
        <Space>
          <Button onClick={onClose}>{t('common.close')}</Button>
          <Button type="primary" icon={<RollbackOutlined />} disabled={!detail.data || version === current} onClick={() => version !== null && onRestore(version)}>
            {t('wf.restore')}
          </Button>
        </Space>
      }
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>{t('wf.restoreHelp')}</Typography.Paragraph>
      <div style={{ height: '62vh', border: '1px solid #eef0f3', borderRadius: 8 }}>
        {!flow ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Spin /></div>
        ) : (
          <ReactFlowProvider>
            <ReactFlow<FlowNode, FlowEdge>
              nodes={flow.nodes}
              edges={flow.edges}
              nodeTypes={nodeTypes}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable={false}
              fitView
              fitViewOptions={{ padding: 0.2, maxZoom: 1.1 }}
            >
              <Background gap={20} color="#e5e7eb" />
              <Controls showInteractive={false} />
            </ReactFlow>
          </ReactFlowProvider>
        )}
      </div>
    </Modal>
  );
}
