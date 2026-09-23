import { createFileRoute, notFound } from "@tanstack/solid-router";
import { jobCard, jobDetail, jobQuery } from "../../relay/DiscoveryOperations";
import { readFragment, readQuery } from "../../relay/fragments";
import type { DiscoveryOperationsJobQuery } from "../../__generated__/DiscoveryOperationsJobQuery.graphql";
import type { DiscoveryOperations_jobDetail$key } from "../../__generated__/DiscoveryOperations_jobDetail.graphql";
import type { DiscoveryOperations_job$key } from "../../__generated__/DiscoveryOperations_job.graphql";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { JobDetail } from "../../features/postings/JobDetail";
import { detailHead, DetailError, DetailNotFound, DetailPending, DetailFailure, loadDetail } from "../../features/discovery/DetailRoute";

export const Route = createFileRoute("/jobs/$id")({
  loader: async ({ context, params }) => {
    const failure = await loadDetail(context.relayEnvironment, jobQuery, params);
    if (failure) return { id: params.id, title: "채용 정보", description: "채용 정보를 다시 확인해 주세요.", failure };
    const key = readQuery<DiscoveryOperationsJobQuery>(context.relayEnvironment, jobQuery, params).jobPosting;
    if (!key) throw notFound();
    const detail = readFragment<DiscoveryOperations_jobDetail$key>(context.relayEnvironment, jobDetail, key);
    const job = readFragment<DiscoveryOperations_job$key>(context.relayEnvironment, jobCard, detail);
    return { id: params.id, title: `${job.title} · ${job.careerSite.displayName}`, description: detail.descriptionText.slice(0, 160), failure };
  },
  staleTime: Infinity,
  head: ({ loaderData }) => detailHead(loaderData?.title ?? "공고를 찾을 수 없어요", loaderData?.description ?? "채용 공고를 확인해 주세요.", `/jobs/${encodeURIComponent(loaderData?.id ?? "")}`),
  notFoundComponent: DetailNotFound, errorComponent: DetailError, pendingComponent: DetailPending,
  component: () => {
    const data = Route.useLoaderData();
    const environment = useRelayEnvironment();
    return data().failure ? <DetailFailure failure={data().failure!} /> : <JobDetail job={readQuery<DiscoveryOperationsJobQuery>(environment(), jobQuery, { id: data().id }).jobPosting!} />;
  },
});
