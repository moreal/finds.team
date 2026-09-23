import { Button } from "./Button";
import "./composites.css";

export interface ToastProps { message: string; onDismiss?: () => void }

/** Keep mounted with an empty message initially so updates are announced. No expiry timer. */
export function Toast(props: ToastProps) {
  return <div class="ui-toast-region">
    <div role="status" aria-live="polite" aria-atomic="true">{props.message}</div>
    {props.message && props.onDismiss && <Button variant="ghost" onClick={props.onDismiss}>알림 닫기</Button>}
  </div>;
}
