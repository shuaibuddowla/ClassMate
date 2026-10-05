export function deliveryPayload(token:string,data:Record<string,string>,platform:string){
 const remaining=data.kind==='blood_request'?Math.floor((Date.parse(data.expires_at)-Date.now())/1000):86400;
 const ttl=String(Number.isFinite(remaining)?Math.max(0,Math.min(86400,remaining)):0);
 // Data-only browser messages are displayed by one service-worker handler.
 // Android retains its exact high-priority payload contract.
 return {message:{token,...(platform==='web'?{webpush:{headers:{Urgency:'high',TTL:ttl}}}:{android:{priority:'HIGH',ttl:ttl+'s'}}),data}};
}
