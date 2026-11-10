/* Cockpit : voiture 3D pré-calculée — vidéos (rotation, plongée) + images (portes)
   Modèle : GT Cars: Hyperspeed (Sketchfab), CC-BY 4.0 */
(function(){
var box=document.getElementById('m3d');if(!box)return;
var FPS=30,NF=480,DI=40,DS=388,POSE=88,RATE=1,ST=20,K=['d1','d2','d3a','d3b','d4','d5'];
var SRCS={n:window.__m3dSrc||{rot:'m3d/rot.mp4',rotl:'m3d/rotl.mp4',dive:'m3d/dive.mp4',dive_r:'m3d/dive_r.mp4'},j:window.__m3dSrcJ||{rot:'m3d/rot_j.mp4',rotl:'m3d/rotl_j.mp4',dive:'m3d/dive_j.mp4',dive_r:'m3d/dive_r_j.mp4'}};
var LIGHT=document.body.classList.contains('clair'),SRC=LIGHT?SRCS.j:SRCS.n,DDIR=LIGHT?'pj':'pn';
box.insertAdjacentHTML('beforeend','<div id="m3v"><video id="mvA" poster="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" src="'+SRC.rot+'" muted playsinline loop preload="auto"></video><video id="mvL" poster="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" src="'+SRC.rotl+'" muted playsinline loop preload="auto"></video><img id="m3i" alt=""></div><video id="mvD" poster="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" muted playsinline preload="auto"></video>');
var VA=document.getElementById('mvA'),VL=document.getElementById('mvL'),IM=document.getElementById('m3i'),VD=document.getElementById('mvD'),V=document.getElementById('m3v');
[VA,VL].forEach(function(v){v.playbackRate=RATE;v.defaultPlaybackRate=RATE;try{v.currentTime=DI/FPS}catch(e){}});
var posed=false,running=false,mode='rot',mask=0,busy=false,dive=0,drag=null,hold=0,cur=VA,closeT=0;
/* images des portes, chargées et décodées à l'avance */
var CACHE={};function img(n){var u='m3d/'+DDIR+'/'+n+'.webp';if(!CACHE[u]){var i=new Image();i.src=u;CACHE[u]=i}return u}
function loadAll(list,cb){var left=list.length,done=false,fin=function(){if(!done){done=true;cb()}};if(!left)return fin();setTimeout(fin,700);list.forEach(function(u){var i=CACHE[u]||new Image();CACHE[u]=i;if(i.complete&&i.naturalWidth){if(--left<=0)fin();return}i.onload=i.onerror=function(){if(--left<=0)fin()};if(!i.src)i.src=u})}
/* seules les images de base sont préparées ; les séquences de portes sont chargées au moment voulu (économise la mémoire du poste) */
function preDoors(){['c00','dw','dl','dlw'].forEach(img)}
function cn(m){return 'c'+(m<10?'0':'')+m}
function lightsOn(){var e=document.getElementById('lights');return !!(e&&e.classList.contains('on'))}
function warnOn(){try{if(localStorage.getItem('m3dWarn')==='1')return true}catch(e){}return !!(window.__carv&&window.__carv.warn===true)}
function curMask(){var o={};try{o=window.__dOpen?window.__dOpen():{}}catch(e){}var m=0;K.forEach(function(k,b){if(o[k])m|=1<<b});if(window.__m3dExp)m=63;return m}
function frameOf(v){return Math.round(v.currentTime*FPS)%NF}
function showVid(v){cur=v;VA.style.opacity=v===VA?1:0;VL.style.opacity=v===VL?1:0;IM.style.opacity=0}
function showImg(u){IM.src=u;IM.style.opacity=1;VA.style.opacity=0;VL.style.opacity=0}
/* tourne jusqu'à une image précise en accélérant la vidéo (toujours vers l'avant, fluide) */
function spinTo(f,cb){busy=true;var v=cur;var d0=(f-frameOf(v)+NF)%NF;if(d0>NF/2){v.pause();var back=function(){var fr=frameOf(v),d=(fr-f+NF)%NF;if(d<=4||d>NF-4){v.currentTime=f/FPS;setTimeout(function(){busy=false;cb&&cb()},60);return}v.currentTime=((fr-(d>60?6:3))+NF)%NF/FPS;setTimeout(back,28)};back();return}var go=function(){var d=(f-frameOf(v)+NF)%NF;if(d<=2||d>NF-2){v.pause();v.currentTime=f/FPS;setTimeout(function(){busy=false;cb&&cb()},60);return}
  v.playbackRate=d>60?4:(d>20?2:1);if(v.paused)v.play().catch(function(){});setTimeout(go,30)};go()}
function seq(list,done){busy=true;loadAll(list,function(){var i=0;(function n(){if(i>=list.length){busy=false;done&&done();return}IM.src=list[i++];setTimeout(n,1000/FPS)})()})}
function doorTo(m){var prev=mask;mask=m;var diff=prev^m,one=diff&&!(diff&(diff-1)),L=[];
  if(one&&((prev|m)===diff)){var b=Math.log2(diff);for(var j=1;j<ST;j++)L.push(img('a'+b+'_'+(j<10?'0':'')+j));if(!m)L.reverse()}
  else if((prev===0&&m===63)||(prev===63&&m===0)){for(j=1;j<ST;j++)L.push(img('x_'+(j<10?'0':'')+j));if(!m)L.reverse()}
  L.push(img(cn(m)));seq(L)}
function staticFrame(){var bl=warnOn()&&(Math.floor(Date.now()/450)%2===0),hl=lightsOn();if(mask)return img(cn(mask));return img(bl&&hl?'dlw':bl?'dw':hl?'dl':'c00')}
function autoOn(){try{return localStorage.getItem('m3dAuto')!=='0'}catch(e){return true}}
function tick(){
  if(document.body.classList.contains('inBg')){if(!VA.paused)VA.pause();if(!VL.paused)VL.pause();return}
  if(!running||dive||busy)return;
  var st=document.getElementById('stage'),pg=st&&st.getAttribute('data-page');
  var m=curMask(),w=warnOn();
  if(mode==='rot'){
    if(m||w){[VA,VL].forEach(function(v){v.playbackRate=RATE});spinTo(DI,function(){mode='door';mask=0;showImg(img('c00'));if(m)doorTo(m)});return}
    var want=lightsOn()?VL:VA;if(want!==cur){want.currentTime=cur.currentTime;if(!cur.paused)want.play().catch(function(){});cur.pause();showVid(want)}
    var vis=pg==='home'||document.body.classList.contains('persOn');
    if(vis&&!drag&&Date.now()>=hold&&!autoOn()){var df=(POSE-frameOf(cur)+NF)%NF;if(df>2&&df<NF-2&&!posed){posed=true;spinTo(POSE);return}if(!cur.paused)cur.pause()}
    else if(!vis||drag||Date.now()<hold){if(!cur.paused&&!drag)cur.pause()}
    else if(cur.paused){posed=false;cur.playbackRate=RATE;cur.play().catch(function(){})}
  }else{
    if(m!==mask){closeT=0;doorTo(m);return}
    if(!m&&!w){if(!closeT)closeT=Date.now()+800;else if(Date.now()>closeT){closeT=0;mode='rot';posed=false;var t=DI/FPS;VA.currentTime=t;VL.currentTime=t;showVid(lightsOn()?VL:VA);return}}
    var u=staticFrame();if(IM.getAttribute('src')!==u)IM.src=u;
  }
}
setInterval(tick,60);
/* curseur de rotation sous la voiture */
(function(){var V0=document.getElementById('m3v');if(!V0)return;var SL=document.createElement('div');SL.id='m3sl';SL.innerHTML='<i class="tr"></i><b class="kn"><svg viewBox="0 0 24 24"><path d="M9 7l-5 5 5 5M15 7l5 5-5 5"/></svg></b>';(document.getElementById('stage')||V0.parentNode).appendChild(SL);
  var KN=SL.querySelector('.kn'),sd=null;
  function pos(){if(!sd&&running&&mode==='rot'&&!dive){var f=(cur.currentTime*FPS%NF)/NF;KN.style.left=(f*100)+'%'}requestAnimationFrame(pos)}requestAnimationFrame(pos);
  function at(e){var r=SL.getBoundingClientRect();return Math.max(0,Math.min(.999,(e.clientX-r.left)/r.width))}
  SL.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive||busy)return;e.preventDefault();e.stopPropagation();SL.setPointerCapture(e.pointerId);cur.pause();posed=true;window.__m3dDrag=true;window.__m3dDragAt=Date.now();sd=1;SL.classList.add('on');var f=at(e);KN.style.left=(f*100)+'%';cur.currentTime=f*NF/FPS});
  SL.addEventListener('pointermove',function(e){if(!sd)return;e.stopPropagation();var f=at(e);KN.style.left=(f*100)+'%';var t=f*NF/FPS;if(Math.abs(t-cur.currentTime)>0.02)cur.currentTime=t});
  function end(){if(!sd)return;sd=null;SL.classList.remove('on');hold=Date.now()+2500;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450)}
  SL.addEventListener('pointerup',end);SL.addEventListener('pointercancel',end);
})();
/* faire tourner la voiture au doigt, sur l'accueil */
function inCar(e){var st=document.getElementById('stage');if(!st)return false;var r=st.getBoundingClientRect(),sc=r.width/1280,x=(e.clientX-r.left)/sc,y=(e.clientY-r.top)/sc;return x>230&&x<1050&&y>150&&y<560}
document.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive||busy)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit')||document.body.classList.contains('persOn'))return;
  if(e.target.closest&&e.target.closest('.gc,.tile,button,#tiles,#top,#m3btn,#m3ctl,.cw,#m3sl'))return;if(!inCar(e))return;
  cur.pause();posed=true;drag={x:e.clientX,t:cur.currentTime,k:0.036};window.__m3dDrag=true;window.__m3dDragAt=Date.now()},true);
document.addEventListener('pointermove',function(e){if(!drag)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280,dur=NF/FPS;
  var t=drag.t+(e.clientX-drag.x)/sc*drag.k;t=((t%dur)+dur)%dur;if(Math.abs(t-cur.currentTime)>0.02)cur.currentTime=t},true);
function up(){if(drag){drag=null;hold=Date.now()+2500;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450)}}
document.addEventListener('pointerup',up,true);document.addEventListener('pointercancel',up,true);
/* plongée dans l'habitacle (onglet Véhicule) */
function diveTo(on,cb){if(on&&dive===1||!on&&dive===0){cb&&cb();return}
  var go=function(){dive=2;VD.src=on?SRC.dive:SRC.dive_r;VD.currentTime=0;VD.style.display='block';VD.style.opacity=on?0:1;
    var started=function(){VD.style.opacity=1;if(on)V.style.opacity=0};VD.onplaying=started;VD.play().catch(function(){started()});
    VD.onended=function(){dive=on?1:0;if(!on){V.style.opacity=1;VD.style.display='none';showVid(cur);cur.currentTime=DS/FPS;posed=false}document.body.classList.toggle('m3dIn',on);cb&&cb()}};
  if(on){if(mode!=='rot'){window.__m3dExp=false;mode='rot';mask=0;showVid(cur)}spinTo(DS,go)}else go()}
new MutationObserver(function(){var pg=document.getElementById('stage').getAttribute('data-page');if(!running)return;if(pg==='veh')diveTo(true);else if(dive===1&&!window.__m3dInside)diveTo(false)}).observe(document.getElementById('stage'),{attributes:true,attributeFilter:['data-page']});
window.M3DV={start:function(){if(!running){running=true;preDoors();if(VA.readyState<2){showImg(img('c00'));var sw=function(){VA.removeEventListener('playing',sw);if(running&&mode==='rot'&&!dive)showVid(VA)};VA.addEventListener('playing',sw)}else showVid(VA);VA.play().catch(function(){})}},
  stop:function(){running=false;VA.pause();VL.pause();if(dive){dive=0;VD.style.display='none';V.style.opacity=1;document.body.classList.remove('m3dIn')}},setColor:function(){},
  theme:function(light){light=!!light;if(light===LIGHT)return;LIGHT=light;SRC=light?SRCS.j:SRCS.n;DDIR=light?'pj':'pn';
    /* fondu : on fige l'image actuelle par-dessus pendant que la vidéo de l'autre thème se charge (évite l'icône lecture) */
    var V=document.getElementById('m3v'),cv=document.getElementById('m3fx');
    if(!cv){cv=document.createElement('canvas');cv.id='m3fx';cv.style.cssText='position:absolute;inset:0;width:100%;height:100%;pointer-events:none;transition:opacity .45s;z-index:3';V.appendChild(cv)}
    try{var srcEl=IM.style.opacity==='1'?IM:cur;cv.width=720;cv.height=360;cv.getContext("2d").drawImage(srcEl,0,0,720,360);cv.style.transition='none';cv.style.opacity=1;cv.offsetWidth;cv.style.transition='opacity .45s'}catch(e){cv.style.opacity=0}
    var t=cur.currentTime,pl=!cur.paused,left=2,fin=function(){if(--left>0)return;setTimeout(function(){cv.style.opacity=0},60)};
    setTimeout(function(){cv.style.opacity=0},2500);
    [VA,VL].forEach(function(v,i){v.pause();v.src=i?SRC.rotl:SRC.rot;var f=function(){v.removeEventListener('loadeddata',f);var sk=function(){v.removeEventListener('seeked',sk);if(pl&&v===cur)v.play().catch(function(){});fin()};v.addEventListener('seeked',sk);try{v.currentTime=t}catch(e){sk()}};v.addEventListener('loadeddata',f);v.load()});
    if(mode==='door'){IM.src=staticFrame()}if(running)preDoors()},
  explode:function(){window.__m3dExp=!window.__m3dExp;return window.__m3dExp},inside:function(){var on=dive!==1;window.__m3dInside=on;diveTo(on);return on}};
window.__m3dOk=1;
})();
