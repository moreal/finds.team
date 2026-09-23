import type { DiscoveryOperations_job$key } from "../../__generated__/DiscoveryOperations_job.graphql";
import { jobCard } from "../../relay/DiscoveryOperations";
import { useFragment } from "../../relay/fragments";
import { PostingCard } from "./PostingCard";

export function FragmentPostingCard(props: { job: DiscoveryOperations_job$key }) {
  const job = useFragment(jobCard, () => props.job);
  return <PostingCard title={job().title} href={`/jobs/${encodeURIComponent(job().id)}`}
    company={job().careerSite.displayName} companyHref={`/companies/${encodeURIComponent(job().careerSite.slug)}`}
    metadata={[job().classification?.location?.displayName].filter((value): value is string => !!value)}
    skills={job().classification?.skills.map(skill => skill.skill?.displayName ?? skill.text)} />;
}
