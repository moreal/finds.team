import "./foundations.css";

/** A static placeholder; its containing data surface owns the loading announcement. */
export function Skeleton(props: { shape?: "line" | "card" | "circle"; class?: string }) {
  return <div aria-hidden="true" class={`ui-skeleton ${props.class ?? ""}`} data-shape={props.shape ?? "line"} />;
}
