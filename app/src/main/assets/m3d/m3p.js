/* Cockpit : voiture 3D pré-calculée (images) — modèle : GT Cars: Hyperspeed (Sketchfab), CC-BY 4.0 */
(function(){
var F=window.M3F||{},box=document.getElementById('m3d');if(!box)return;
var U={};function url(set,i){var k=set+i,a=F[set];if(U[k])return U[k];if(!a||!a[i])return '';var s=a[i],t=s.charAt(0)==='/'?'image/jpeg':'image/webp';
  try{var b=atob(s),n=new Uint8Array(b.length);for(var j=0;j<b.length;j++)n[j]=b.charCodeAt(j);U[k]=URL.createObjectURL(new Blob([n],{type:t}))}catch(e){U[k]='data:'+t+';base64,'+s}return U[k]}
box.insertAdjacentHTML('beforeend','<div id="m3v"><img id="m3a" alt=""><img id="m3b" alt=""><img id="m3h" class="glw" alt=""><img id="m3s" class="glw" alt=""></div><img id="m3dv" alt="">');
var V=document.getElementById('m3v'),IA=document.getElementById('m3a'),IB=document.getElementById('m3b'),IH=document.getElementById('m3h'),IS=document.getElementById('m3s'),DV=document.getElementById('m3dv');
if(F.rq&&F.rot&&!F.rot2){var R2=[];for(var z=0;z<F.rot.length;z++){R2.push(F.rot[z]);if(F.rq[z])R2.push(F.rq[z])}F.rot=R2;F.rot2=1;
  if(F.gq){var g2=[],s2=[];for(z=0;z<F.gh.length;z++){g2.push(F.gh[z]);s2.push(F.gs[z]);if(F.gq[z]){g2.push(F.gq[z]);s2.push(F.sq[z])}}F.gh=g2;F.gs=s2}}
var N=(F.rot||[]).length||120,idx=10,mode='rot',mask=0,shown=-1,drag=null,hold=0,running=false,busy=false,closeT=0,dive=0,diveQ=null;
var K=['d1','d2','d3a','d3b','d4','d5'];
var DI=Math.round(N*10/120),DS=Math.round(N*97/120),spin=false;
/* fait tourner la voiture jusqu'à un angle précis, par le chemin le plus court */
function spinTo(t,cb){spin=true;var go=function(){var d=((t-Math.round(idx))%N+N+N/2)%N-N/2;if(Math.abs(d)<1){idx=t;set(IA,url('rot',t));spin=false;cb&&cb();return}
  var st=Math.max(1,Math.min(4,Math.round(Math.abs(d)/6)));idx=((Math.round(idx)+(d>0?st:-st))%N+N)%N;for(var q=1;q<=4;q++)pre(url('rot',((Math.round(idx)+(d>0?q:-q))%N+N)%N));
  set(IA,url('rot',Math.round(idx)));glow(false);setTimeout(go,40)};go()}
var DEC={},DQ=[];
function pre(src){if(!src||DEC[src])return DEC[src];var im=new Image();im.decoding='async';im.src=src;DEC[src]=im;im.ok=false;(im.decode?im.decode():Promise.resolve()).then(function(){im.ok=true},function(){im.ok=true});DQ.push(src);if(DQ.length>40){var o=DQ.shift();delete DEC[o]}return im}
function set(img,src){if(img.getAttribute('data-s')===src)return true;var d=pre(src);if(d&&!d.ok&&img.getAttribute('data-s'))return false;img.setAttribute('data-s',src);img.src=src;return true}
function lightsOn(){var e=document.getElementById('lights');return !!(e&&e.classList.contains('on'))}
function warnOn(){try{if(localStorage.getItem('m3dWarn')==='1')return true}catch(e){}return !!(window.__carv&&window.__carv.warn===true)}
function curMask(){var o={};try{o=window.__dOpen?window.__dOpen():{}}catch(e){}var m=0;K.forEach(function(k,b){if(o[k])m|=1<<b});if(window.__m3dExp)m=63;return m}
function fade(fn){V.style.opacity=0;setTimeout(function(){fn();setTimeout(function(){V.style.opacity=1},40)},180)}
function play(list,done){busy=true;list.forEach(pre);var i=0,w=0;(function n(){if(i>=list.length){busy=false;done&&done();return}if(set(IA,list[i])||++w>6){i++;w=0}setTimeout(n,70)})()}
function glow(isDoor){var hl=lightsOn(),bl=warnOn()&&(Math.floor(Date.now()/450)%2===0);
  if(hl)set(IH,isDoor?url('dgh',0):url('gh',Math.round(idx)%N));if(warnOn())set(IS,isDoor?url('dgs',0):url('gs',Math.round(idx)%N));IH.style.opacity=hl?1:0;IS.style.opacity=bl?1:0}
function doorTo(m){var prev=mask;mask=m;var diff=prev^m,one=diff&&!(diff&(diff-1)),seq=[];
  if(one&&((prev|m)===diff)){var b=Math.log2(diff),fr=[0,1,2,3,4].map(function(j){return url('a'+b,j)});seq=m?fr:fr.reverse()}
  else if((prev===0&&m===63)||(prev===63&&m===0)){var x=[0,1,2,3,4].map(function(j){return url('x',j)});seq=m?x:x.reverse()}
  seq.push(url('c',m));play(seq)}
function tick(){
  if(!running)return;var st=document.getElementById('stage'),pg=st&&st.getAttribute('data-page');
  if(document.hidden)return;
  if(dive||busy||spin)return;
  var m=curMask();
  if(mode==='rot'){
    if(m){IB.style.opacity=0;spinTo(DI,function(){mode='door';mask=0;pre(url('c',0));set(IA,url('c',0));glow(true);doorTo(m)});return}
    if(pg!=='home'&&!document.body.classList.contains('persOn'))return;
    var nx=idx;if(!drag&&Date.now()>hold)nx=(idx+0.5)%N;
    var a=Math.round(nx)%N;for(var q=1;q<=6;q++)pre(url('rot',(a+q)%N));
    if(set(IA,url('rot',a)))idx=nx;IB.style.opacity=0;glow(false);
  }else if(mode==='door'){
    if(m!==mask){closeT=0;doorTo(m);return}
    if(!m){if(!closeT)closeT=Date.now()+900;else if(Date.now()>closeT){closeT=0;mode='rot';idx=DI;set(IA,url('rot',DI));glow(false)}}
    set(IA,url('c',mask));glow(true);
  }
}
setInterval(tick,50);
/* glisser pour tourner */
document.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit')||document.body.classList.contains('persOn'))return;
  var t=e.target;if(!(t.id==='p-home'||t.id==='m3v'||(t.parentNode&&t.parentNode.id==='m3v')))return;drag={x:e.clientX,i:idx}},true);
document.addEventListener('pointermove',function(e){if(!drag)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280;idx=((drag.i-(e.clientX-drag.x)/sc*0.16)%N+N)%N},true);
function up(){if(drag){drag=null;hold=Date.now()+4000}}
document.addEventListener('pointerup',up,true);document.addEventListener('pointercancel',up,true);
/* plongée dans l'habitacle */
function diveTo(on,cb){var n=(F.v||[]).length;if(!n){cb&&cb();return}
  if(on&&dive===1||!on&&dive===0){cb&&cb();return}
  if(mode==='door'){window.__m3dExp=false}
  dive=2;var go=function(){var i=on?0:n-1;V.style.opacity=on?1:0;DV.style.display='block';
    (function s(){set(DV,url('v',i));if(on&&i===1)V.style.opacity=0;i+=on?1:-1;if(on?i<n:i>=0)setTimeout(s,55);else{dive=on?1:0;if(!on){DV.style.display='none';V.style.opacity=1}document.body.classList.toggle('m3dIn',on);cb&&cb()}})()};
  for(var w=0;w<n;w++)pre(url('v',w));
  if(on&&mode==='rot'){spinTo(DS,go)}
  else go()}
new MutationObserver(function(){var pg=document.getElementById('stage').getAttribute('data-page');if(!running)return;if(pg==='veh')diveTo(true);else if(dive===1&&!window.__m3dInside)diveTo(false)}).observe(document.getElementById('stage'),{attributes:true,attributeFilter:['data-page']});
window.M3DV={start:function(){running=true},stop:function(){running=false;if(dive){dive=0;DV.style.display='none';V.style.opacity=1;document.body.classList.remove('m3dIn')}},setColor:function(){},
  explode:function(){window.__m3dExp=!window.__m3dExp;return window.__m3dExp},inside:function(){var on=dive!==1;window.__m3dInside=on;diveTo(on);return on}};
window.__m3dOk=1;
})();
