import { useQuery } from "@tanstack/react-query";
import { readAcademic, saveAcademic } from "./cache";
export function useAcademic<T>(
  user: string,
  batch: string,
  kind: string,
  fetcher: () => Promise<T>,
  suffix: string[] = [],
) {
  const cacheKind = [kind, ...suffix].join(":");
  return useQuery({
    queryKey: [user, batch, kind, ...suffix],
    enabled: !!batch,
    networkMode: "always",
    queryFn: async () => {
      if (!navigator.onLine) {
        const old = readAcademic(user, batch, cacheKind);
        if (old) return old.data as T;
        throw new Error("No saved content yet. Connect once to sync.");
      }
      const data = await fetcher();
      saveAcademic(user, batch, cacheKind, data);
      return data;
    },
    initialData: () =>
      readAcademic(user, batch, cacheKind)?.data as T | undefined,
    initialDataUpdatedAt: () =>
      readAcademic(user, batch, cacheKind)?.syncedAt || 0,
  });
}
