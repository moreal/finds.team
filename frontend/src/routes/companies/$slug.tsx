import { createFileRoute, notFound } from "@tanstack/solid-router";
import { companyPage, companyQuery } from "../../relay/DiscoveryOperations";
import { readFragment, readQuery } from "../../relay/fragments";
import type { DiscoveryOperationsCompanyQuery } from "../../__generated__/DiscoveryOperationsCompanyQuery.graphql";
import type { DiscoveryOperations_company$key } from "../../__generated__/DiscoveryOperations_company.graphql";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { CompanyPage } from "../../features/companies/CompanyPage";
import { detailHead, DetailError, DetailNotFound, DetailPending, DetailFailure, loadDetail } from "../../features/discovery/DetailRoute";

export const Route = createFileRoute("/companies/$slug")({
  loader: async ({ context, params }) => {
    const failure = await loadDetail(context.relayEnvironment, companyQuery, params);
    if (failure) return { slug: params.slug, name: "회사", failure };
    const key = readQuery<DiscoveryOperationsCompanyQuery>(context.relayEnvironment, companyQuery, params).careerSite;
    if (!key) throw notFound();
    const company = readFragment<DiscoveryOperations_company$key>(context.relayEnvironment, companyPage, key);
    return { slug: params.slug, name: company.displayName, failure };
  },
  staleTime: Infinity,
  head: ({ loaderData }) => detailHead(`${loaderData?.name ?? "회사"} 채용`, `${loaderData?.name ?? "회사"}의 채용 중인 공고와 최근 확인 정보를 살펴보세요.`, `/companies/${encodeURIComponent(loaderData?.slug ?? "")}`),
  notFoundComponent: DetailNotFound, errorComponent: DetailError, pendingComponent: DetailPending,
  component: () => {
    const data = Route.useLoaderData(); const environment = useRelayEnvironment();
    return data().failure ? <DetailFailure failure={data().failure!} /> : <CompanyPage company={readQuery<DiscoveryOperationsCompanyQuery>(environment(), companyQuery, { slug: data().slug }).careerSite!} />;
  },
});
