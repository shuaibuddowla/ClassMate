export function deliveryPayload(token:string,data:Record<string,string>,platform:string){
 // Data-only browser messages are displayed by one service-worker handler.
 // Android retains its exact high-priority payload contract.
 return {message:{token,...(platform==='web'?{webpush:{headers:{Urgency:'high',TTL:'86400'}}}:{android:{priority:'HIGH',ttl:'86400s'}}),data}};
}
