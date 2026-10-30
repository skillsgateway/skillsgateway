import { MutationCache, QueryCache, QueryClient, type QueryClientConfig } from "@tanstack/react-query";
import { ApiError } from "./client";

const ME = ["me"];

/** A refusal for lack of a role: the server answers 403 to every one, whatever the cause. */
function refused(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/**
 * The portal's query client. A refused request means the session's roles are no longer what the
 * portal last read, so the session is read again and every role-dependent surface follows it
 * without a reload. An unchanged answer is structurally shared, so it re-renders nothing and
 * cannot loop. A refusal is not retried: the role will not appear between two attempts.
 *
 * @Requirements GW_AUTH_0066
 */
export function createQueryClient(config: QueryClientConfig = {}): QueryClient {
  const client: QueryClient = new QueryClient({
    ...config,
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (refused(error) && query.queryKey[0] !== ME[0]) {
          void client.invalidateQueries({ queryKey: ME });
        }
      },
    }),
    mutationCache: new MutationCache({
      onError: (error) => {
        if (refused(error)) {
          void client.invalidateQueries({ queryKey: ME });
        }
      },
    }),
    defaultOptions: {
      ...config.defaultOptions,
      queries: {
        retry: (failures, error) => !refused(error) && failures < 1,
        ...config.defaultOptions?.queries,
      },
    },
  });
  return client;
}
