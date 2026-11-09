/* Cockpit : voiture 3D pré-calculée — vidéos (rotation, plongée) + images (portes)
   Modèle : GT Cars: Hyperspeed (Sketchfab), CC-BY 4.0 */
(function(){
var box=document.getElementById('m3d');if(!box)return;
var FPS=30,NF=480,DI=40,DS=388,POSE=88,RATE=1,ST=20,K=['d1','d2','d3a','d3b','d4','d5'];
var SRCS={n:window.__m3dSrc||{rot:'m3d/rot.mp4',rotl:'m3d/rotl.mp4',dive:'m3d/dive.mp4',dive_r:'m3d/dive_r.mp4'},j:window.__m3dSrcJ||{rot:'m3d/rot_j.mp4',rotl:'m3d/rotl_j.mp4',dive:'m3d/dive_j.mp4',dive_r:'m3d/dive_r_j.mp4'}};
var LIGHT=document.body.classList.contains('clair'),SRC=LIGHT?SRCS.j:SRCS.n,DDIR=LIGHT?'pj':'pn';
box.insertAdjacentHTML('beforeend','<div id="m3v"><video id="mvA" src="'+SRC.rot+'" muted playsinline loop preload="auto"></video><video id="mvL" src="'+SRC.rotl+'" muted playsinline loop preload="auto"></video><img id="m3i" alt=""></div><video id="mvD" muted playsinline preload="auto"></video>');
var VA=document.getElementById('mvA'),VL=document.getElementById('mvL'),IM=document.getElementById('m3i'),VD=document.getElementById('mvD'),V=document.getElementById('m3v');
[VA,VL].forEach(function(v){v.playbackRate=RATE;v.defaultPlaybackRate=RATE;try{v.currentTime=DI/FPS}catch(e){}});
var posed=false,running=false,mode='rot',mask=0,busy=false,dive=0,drag=null,hold=0,cur=VA,closeT=0;
/* images des portes, chargées et décodées à l'avance */
var CACHE={};function img(n){var u='m3d/'+DDIR+'/'+n+'.webp';if(!CACHE[u]){var i=new Image();i.src=u;CACHE[u]=i;if(i.decode)i.decode().catch(function(){})}return u}
function preDoors(){for(var m=0;m<64;m++)img('c'+(m<10?'0':'')+m);for(var b=0;b<6;b++)for(var j=1;j<ST;j++)img('a'+b+'_'+(j<10?'0':'')+j);for(j=1;j<ST;j++)img('x_'+(j<10?'0':'')+j);['dw','dl','dlw'].forEach(img)}
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
function seq(list,done){busy=true;var i=0;(function n(){if(i>=list.length){busy=false;done&&done();return}IM.src=list[i++];setTimeout(n,1000/FPS)})()}
function doorTo(m){var prev=mask;mask=m;var diff=prev^m,one=diff&&!(diff&(diff-1)),L=[];
  if(one&&((prev|m)===diff)){var b=Math.log2(diff);for(var j=1;j<ST;j++)L.push(img('a'+b+'_'+(j<10?'0':'')+j));if(!m)L.reverse()}
  else if((prev===0&&m===63)||(prev===63&&m===0)){for(j=1;j<ST;j++)L.push(img('x_'+(j<10?'0':'')+j));if(!m)L.reverse()}
  L.push(img(cn(m)));seq(L)}
function staticFrame(){var bl=warnOn()&&(Math.floor(Date.now()/450)%2===0),hl=lightsOn();if(mask)return img(cn(mask));return img(bl&&hl?'dlw':bl?'dw':hl?'dl':'c00')}
function autoOn(){try{return localStorage.getItem('m3dAuto')!=='0'}catch(e){return true}}
function tick(){
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
/* faire tourner la voiture au doigt, sur l'accueil */
function inCar(e){var st=document.getElementById('stage');if(!st)return false;var r=st.getBoundingClientRect(),sc=r.width/1280,x=(e.clientX-r.left)/sc,y=(e.clientY-r.top)/sc;return x>230&&x<1050&&y>150&&y<560}
document.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive||busy)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit')||document.body.classList.contains('persOn'))return;
  if(e.target.closest&&e.target.closest('.gc,.tile,button,#tiles,#top,#m3btn,#m3ctl,.cw'))return;if(!inCar(e))return;
  cur.pause();posed=true;drag={x:e.clientX,t:cur.currentTime};window.__m3dDrag=true},true);
document.addEventListener('pointermove',function(e){if(!drag)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280,dur=NF/FPS;
  var t=drag.t-(e.clientX-drag.x)/sc*0.012;t=((t%dur)+dur)%dur;if(Math.abs(t-cur.currentTime)>0.02)cur.currentTime=t},true);
function up(){if(drag){drag=null;hold=Date.now()+2500;setTimeout(function(){window.__m3dDrag=false},50)}}
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
    var t=cur.currentTime,pl=!cur.paused;[VA,VL].forEach(function(v,i){v.src=i?SRC.rotl:SRC.rot;var f=function(){v.removeEventListener('loadeddata',f);try{v.currentTime=t}catch(e){}if(pl&&v===cur)v.play().catch(function(){})};v.addEventListener('loadeddata',f);v.load()});
    if(mode==='door'){IM.src=staticFrame()}if(running)preDoors()},
  explode:function(){window.__m3dExp=!window.__m3dExp;return window.__m3dExp},inside:function(){var on=dive!==1;window.__m3dInside=on;diveTo(on);return on}};
window.__m3dOk=1;
})();
