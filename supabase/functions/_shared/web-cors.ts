// Android requests without Origin retain their existing behavior. Browser origins
// must be explicitly configured per environment; never reflect arbitrary origins.
export async function withWebCors(request:Request,handler:(request:Request)=>Promise<Response>):Promise<Response>{
 const origin=request.headers.get('Origin');
 const allowed=(Deno.env.get('CLASSMATE_WEB_ORIGINS')||'').split(',').map(value=>value.trim()).filter(Boolean);
 if(origin&&!allowed.includes(origin))return new Response('Origin not allowed',{status:403});
 const headers=new Headers({'Vary':'Origin','Access-Control-Allow-Methods':'POST, OPTIONS','Access-Control-Allow-Headers':'authorization, apikey, content-type, x-client-info','Access-Control-Max-Age':'600'});
 if(origin)headers.set('Access-Control-Allow-Origin',origin);
 if(request.method==='OPTIONS')return new Response(null,{status:204,headers});
 const response=await handler(request);const merged=new Headers(response.headers);headers.forEach((value,key)=>merged.set(key,value));return new Response(response.body,{status:response.status,headers:merged});
}
