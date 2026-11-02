'use strict';
const $=id=>document.getElementById(id);
const status=$('status'), list=$('requests');
async function api(path, method='GET', body) {
  const r=await fetch(path,{method,cache:'no-store',headers:{Authorization:'Bearer '+$('rx').value,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  const data=await r.json();
  if(!r.ok)throw new Error(data.error||'Errore HTTP '+r.status);
  return data;
}
async function refresh(){
  list.replaceChildren();status.textContent='Caricamento…';status.className='';
  try{
    const data=await api('/v1/pair/pending');
    status.textContent=data.requests.length ? 'Richieste in attesa: '+data.requests.length : 'Nessuna richiesta in attesa.';
    for (const req of data.requests){
      const box=document.createElement('article');box.className='request';
      const label=document.createElement('div');label.textContent=req.name;
      const code=document.createElement('div');code.className='code';code.textContent=req.code;
      const b=document.createElement('button');b.textContent='Approva questa richiesta';
      b.addEventListener('click',async()=>{
        b.disabled=true;status.textContent='Invio autorizzazione…';
        try{
          await api('/v1/pair/approve/'+req.id,'POST',{txToken:$('tx').value});
          status.textContent='Autorizzata: il telefono TX può ricevere la chiave.';status.className='success';
          $('tx').value='';await refresh();
        }catch(e){status.textContent=e.message;status.className='error';b.disabled=false;}
      });
      box.append(label,code,b);list.append(box);
    }
  }catch(e){status.textContent=e.message;status.className='error';}
}
$('refresh').addEventListener('click',refresh);
// While this tab is open, RX receives new enrollment requests without pressing Refresh.
setInterval(() => {
  if ($('rx').value.length >= 32 && document.visibilityState === 'visible') refresh();
}, 10000);
