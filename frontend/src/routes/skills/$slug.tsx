import { createFileRoute, notFound } from "@tanstack/solid-router";
import { skillPage, skillQuery } from "../../relay/DiscoveryOperations";
import { readFragment, readQuery } from "../../relay/fragments";
import type { DiscoveryOperationsSkillQuery } from "../../__generated__/DiscoveryOperationsSkillQuery.graphql";
import type { DiscoveryOperations_skill$key } from "../../__generated__/DiscoveryOperations_skill.graphql";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { SkillPage } from "../../features/skills/SkillPage";
import { detailHead, DetailError, DetailNotFound, DetailPending, DetailFailure, loadDetail } from "../../features/discovery/DetailRoute";

export const Route = createFileRoute("/skills/$slug")({
  loader: async ({ context, params }) => {
    const failure = await loadDetail(context.relayEnvironment, skillQuery, params);
    if (failure) return { slug: params.slug, name: "기술", failure };
    const key = readQuery<DiscoveryOperationsSkillQuery>(context.relayEnvironment, skillQuery, params).skill;
    if (!key) throw notFound();
    const skill = readFragment<DiscoveryOperations_skill$key>(context.relayEnvironment, skillPage, key);
    return { slug: params.slug, name: skill.displayName, failure };
  },
  staleTime: Infinity,
  head: ({ loaderData }) => detailHead(`#${loaderData?.name ?? "기술"} 채용`, `${loaderData?.name ?? "기술"} 관련 회사와 채용 공고, 함께 사용하는 기술을 찾아보세요.`, `/skills/${encodeURIComponent(loaderData?.slug ?? "")}`),
  notFoundComponent: DetailNotFound, errorComponent: DetailError, pendingComponent: DetailPending,
  component: () => {
    const data = Route.useLoaderData(); const environment = useRelayEnvironment();
    return data().failure ? <DetailFailure failure={data().failure!} /> : <SkillPage skill={readQuery<DiscoveryOperationsSkillQuery>(environment(), skillQuery, { slug: data().slug }).skill!} />;
  },
});
