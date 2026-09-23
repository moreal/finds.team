import { For } from "solid-js";
import { Link } from "../../ui/Link";
import { Badge } from "../../ui/Badge";
import "../../ui/composites.css";

export interface PostingCardProps {
  title: string; href: string; company: string; companyHref?: string;
  metadata?: readonly string[]; skills?: readonly string[];
}

export function PostingCard(props: PostingCardProps) {
  return <article class="ui-posting-card">
    <h2><Link href={props.href}>{props.title}</Link></h2>
    <p>{props.companyHref ? <Link href={props.companyHref}>{props.company}</Link> : props.company}</p>
    {props.metadata?.length ? <ul class="ui-inline-list" aria-label="근무 조건"><For each={props.metadata}>{item => <li>{item}</li>}</For></ul> : null}
    {props.skills?.length ? <ul class="ui-inline-list" aria-label="기술"><For each={props.skills}>{skill => <li><Badge>{skill}</Badge></li>}</For></ul> : null}
  </article>;
}
