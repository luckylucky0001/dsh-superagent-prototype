import { Tooltip, Typography } from 'antd';
import type { NodeDescriptor } from '@/api/types';
import { useT } from '@/app/i18n';
import { CATEGORY_COLORS, CATEGORY_ORDER } from './graph';
import { CATEGORY_ICONS } from './FlowNode';

export const DND_TYPE = 'application/fellowloop-component';

/** Components grouped by category; drag onto the canvas or click to add. */
export function Palette({ components, onAdd }: { components: NodeDescriptor[]; onAdd: (d: NodeDescriptor) => void }) {
  const t = useT();
  const cats = [...new Set([...CATEGORY_ORDER, ...components.map((c) => c.category)])];
  return (
    <div className="fellowloop-palette">
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('wf.paletteHint')}</Typography.Text>
      {cats.map((cat) => {
        const list = components.filter((c) => c.category === cat);
        if (list.length === 0) return null;
        return (
          <div key={cat} style={{ marginTop: 12 }}>
            <Typography.Text strong style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5, color: CATEGORY_COLORS[cat] }}>
              {CATEGORY_ICONS[cat]} {t.dyn(`wf.category.${cat}`, cat)}
            </Typography.Text>
            <div style={{ marginTop: 6 }}>
              {list.map((c) => (
                <Tooltip key={c.type} title={c.description} placement="right" mouseEnterDelay={0.4}>
                  <div
                    className="fellowloop-palette-item"
                    style={{ borderInlineStartColor: CATEGORY_COLORS[cat] }}
                    draggable
                    onDragStart={(e) => {
                      e.dataTransfer.setData(DND_TYPE, c.type);
                      e.dataTransfer.effectAllowed = 'move';
                    }}
                    onClick={() => onAdd(c)}
                  >
                    <div>{c.label}</div>
                    <div className="fellowloop-mono" style={{ fontSize: 10, color: '#8c8c8c' }}>{c.type}</div>
                  </div>
                </Tooltip>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
