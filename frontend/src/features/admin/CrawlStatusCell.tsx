import { Badge } from "../../ui/Badge";
import "../../ui/composites.css";

const statuses = {
  pending: { label: "대기", tone: "neutral" },
  running: { label: "수집 중", tone: "action" },
  succeeded: { label: "완료", tone: "success" },
  failed: { label: "실패", tone: "danger" },
  delayed: { label: "지연", tone: "warning" },
} as const;
export interface CrawlStatusCellProps {
  status: keyof typeof statuses; count?: number; updatedAt?: string; timeLabel?: string;
}

export function CrawlStatusCell(props: CrawlStatusCellProps) {
  return <div class="ui-crawl-status ui-tabular">
    <Badge tone={statuses[props.status].tone}>{statuses[props.status].label}</Badge>
    {props.count !== undefined && <span>{props.count}건</span>}
    {props.updatedAt && <time datetime={props.updatedAt}>{props.timeLabel ?? props.updatedAt}</time>}
  </div>;
}
