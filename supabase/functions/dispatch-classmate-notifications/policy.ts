export function retryDelay(failures: number, header: string | null, now = Date.now(), random = Math.random()): number {
  const seconds = header ? Number(header) : NaN;
  const advertised = Number.isFinite(seconds) ? seconds : header ? (Date.parse(header)-now)/1000 : 0;
  return Math.min(2147483647,Math.ceil(Math.max(60, Number.isFinite(advertised) ? advertised : 0, Math.min(3600,60*2**Math.min(failures,6))*(1+random*0.25))));
}
export function classify(status: number, code?: string): "invalid" | "retry" | "failed" {
  if(code==="UNREGISTERED") return "invalid";
  return status===429 || status>=500 || status===401 || status===403 ? "retry" : "failed";
}
