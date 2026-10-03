import { classify,retryDelay } from "./policy.ts";
function check(value:boolean,message:string) { if(!value) throw Error(message); }
Deno.test("FCM Retry-After and exponential backoff",()=>{
  check(retryDelay(0,"120",0,0)===120,"numeric Retry-After ignored");
  check(retryDelay(0,new Date(240000).toUTCString(),0,0)===240,"date Retry-After ignored");
  check(retryDelay(0,"90000",0,0)===90000,"long Retry-After shortened");
  check(retryDelay(3,null,0,0)===480,"failure backoff incorrect");
  check(retryDelay(0,null,0,1)>=60,"retry is too early");
});
Deno.test("FCM failure classifications",()=>{
  check(classify(404,"UNREGISTERED")==="invalid","invalid token retained");
  check(classify(429)==="retry" && classify(503)==="retry","temporary failure became permanent");
  check(classify(400,"INVALID_ARGUMENT")==="failed","malformed message should not loop");
});
