import { Badge } from "./ui";
import { ORDER_STATUS_LABEL } from "@/lib/format";
import type { OrderStatus } from "@/lib/types";

const TONE = { PENDING_PAYMENT: "amber", CONFIRMED: "green", EXPIRED: "slate" } as const;

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return <Badge tone={TONE[status]}>{ORDER_STATUS_LABEL[status]}</Badge>;
}
