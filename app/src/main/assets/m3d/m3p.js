/* Cockpit : voiture 3D pré-calculée — moteur v4 (3.0)
   Une seule vidéo décodée, recomposée par la carte graphique (WebGL) en couches :
   décor (showroom) · voiture et plateau · lumière des LED (une couleur) · couleur de carrosserie · plaque d'immatriculation.
   Image vidéo 720×1080 : [couleur prémultipliée] / [transparence] / [LED (moitié gauche) | peinture (moitié droite)].
   Sans WebGL (ou si le poste ne suit pas) : mode léger, la voiture seule sur fond sombre — la 3D ne casse jamais.
   Modèle : Golf 6 (Sketchfab), CC-BY 4.0. Interface publique M3DV inchangée. */
(function(){
var box=document.getElementById('m3d');if(!box)return;

/* ---------- Réglages (modifiables ici) ---------- */
var CFG=Object.assign({
  FPS:30,          // images par seconde des vidéos
  NF:960,          // images par tour dans la vidéo de rotation
  ROT_SPEED:1,     // vitesse de rotation auto : 1 = un tour en 32 s
  EASE_MS:900,     // démarrage progressif de la rotation
  RESUME_MS:2500,  // reprise de la rotation après un geste du doigt
  CLOSE_MS:1200,   // reprise de la rotation après la fermeture des portes
  FADE_MS:180,     // fondu entre deux états
  LOOK_MS:600,     // fondu de décor et glissement de la couleur des LED
  CACHE_MAX:18,    // nombre maximum d'images gardées en mémoire
  DRAG_K:0.072,    // sensibilité de la rotation au doigt
  MIN_FPS:12       // en dessous (mesuré au démarrage), bascule en mode léger
},window.__m3dCfg||{});
var FPS=CFG.FPS,NF=CFG.NF,DUR=NF/FPS;
var VIEW={R:{p:'c',f:Math.round(NF*40/480)},G:{p:'g',f:Math.round(NF*440/480)},T:{p:'t',f:-1}};
var DS=Math.round(NF*388/480),POSE=Math.round(NF*88/480),K=['d1','d2','d3a','d3b','d4','d5'];
var LEFT=1|4,RIGHT=2|8;
var SRC=window.__m3dSrc||{rot:'m3d/rot.mp4',rotl:'m3d/rotl.mp4',rot_j:'m3d/rot_j.mp4',rotl_j:'m3d/rotl_j.mp4',dive:'m3d/dive.mp4',dive_r:'m3d/dive_r.mp4',dive_j:'m3d/dive_j.mp4',dive_r_j:'m3d/dive_r_j.mp4'};
var LIGHT=document.body.classList.contains('clair');
/* 3.0 : plusieurs voitures. La Golf 6 garde ses fichiers à la racine de m3d/, la 207 dans m3d/207/ (3 portes : pas de portes arrière, pas de plongée dans l'habitacle) */
var CARS={golf6:{dir:'m3d/golf6/',rear:true,hood:true,dive:true,top:true},p207:{dir:'m3d/207/',rear:false,hood:false,dive:false,top:true}};   /* fichiers des packs téléchargés, servis par l'appli */
function hasCar(){var c=window.CockpitLook&&CockpitLook.car?CockpitLook.car():null;return CARS[c]?c:null}
function carId(){return hasCar()||'golf6'}
/* portes absentes du modèle (207 : pas de portes arrière ni de capot qui s'ouvre) */
function noMask(){return (CARS[CAR].rear?0:12)|(CARS[CAR].hood?0:32)}
var GIF='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
var LOOK=window.CockpitLook;
function lsGet(k,d){try{var v=localStorage.getItem(k);return v===null?d:v}catch(e){return d}}
function lsPut(k,v){try{localStorage.setItem(k,v)}catch(e){}}
var CAR=carId();

/* ---------- Éléments ---------- */
box.insertAdjacentHTML('beforeend','<div id="m3v"><canvas id="m3gl" width="720" height="360"></canvas><video id="mvA" poster="'+GIF+'" muted playsinline loop preload="auto"></video><img id="m3i" alt=""><img id="m3i2" alt=""></div><video id="mvD" poster="'+GIF+'" muted playsinline preload="none"></video>');
var VA=document.getElementById('mvA'),VD=document.getElementById('mvD'),V=document.getElementById('m3v'),CV=document.getElementById('m3gl');
var L1=document.getElementById('m3i'),L2=document.getElementById('m3i2');
V.style.overflow='hidden';
CV.style.cssText='position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:1';
/* la vidéo reste dans la page (le décodeur doit tourner) mais n'est pas affichée en mode WebGL */
VA.style.cssText='position:absolute;left:0;top:0;width:2px;height:2px;opacity:0;pointer-events:none';
[L1,L2].forEach(function(l){l.style.cssText='position:absolute;left:0;top:0;width:2px;height:2px;opacity:0;pointer-events:none'});

var running=false,mode='rot',mask=0,view='R',dive=0,drag=null,hold=0,closeT=0,posed=false,litSrc=false,litL=null;
/* mode jour : même voiture sur le plateau clair (vidéos et vues _j) */
function rotSrc(lit){return CARS[CAR].dir+(LIGHT?(lit?'rotl_j.mp4':'rot_j.mp4'):(lit?'rotl.mp4':'rot.mp4'))}
var ST=window.__m3dStats={doorMs:[],imgs:0,videosActive:0,renderer:'',lite:false,fps:0,vfps:0};

/* ---------- plaques : coins par image (fichier fabriqué au calcul) ---------- */
var PLATES=null;function loadPlates(){var c=CAR,x=new XMLHttpRequest();x.open('GET',CARS[c].dir+'plates.json');x.onload=function(){if(c!==CAR)return;try{PLATES=JSON.parse(x.responseText)}catch(e){}};x.send()}loadPlates();

/* ============================================================
   gpu : recomposition WebGL (ou null si indisponible)
   ============================================================ */
var gpu=(function(){
  /* mode léger mémorisé 7 jours au plus, puis on réessaie (le poste a pu être mis à jour) */
  /* 3.0.1 : le mode léger a pu être activé à tort (accès aux fichiers bloqué, corrigé) : on l'efface une fois */
  if(lsGet('m3dLiteV','')!=='2'){lsPut('m3dLite','0');lsPut('m3dLiteV','2')}
  var lt=parseInt(lsGet('m3dLite','0'),10)||0;if(lt===1||lt>Date.now()-7*864e5){ST.liteReason=ST.liteReason||'mémorisé';return null}if(lt)lsPut('m3dLite','0');
  var gl=null;try{gl=CV.getContext('webgl',{alpha:false,antialias:false,premultipliedAlpha:false,preserveDrawingBuffer:true})||CV.getContext('experimental-webgl')}catch(e){}
  if(!gl)return null;
  var VS='attribute vec2 p;varying vec2 uv;void main(){uv=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0.,1.);}';
  var FS=['precision mediump float;varying vec2 uv;',
   'uniform sampler2D uV,uI1,uI2,uD0,uD1,uP;uniform float uO1,uO2,uDec,uGain,uPl;uniform vec3 uLed,uPaint;',
   'uniform mat3 uH[6];uniform float uL[6];',
   'const float T=1./3.;',
   'vec3 plate(vec3 c,mat3 H,float L){if(L<=0.)return c;vec3 q=H*vec3(uv.x*720.,uv.y*360.,1.);vec2 t=q.xy/q.z;',
   ' if(t.x<0.||t.x>1.||t.y<0.||t.y>1.)return c;vec4 p=texture2D(uP,t);return mix(c,p.rgb*L,p.a);}',
   'vec3 car(sampler2D s,vec3 dec,int k){',
   ' vec3 col=texture2D(s,vec2(uv.x,uv.y*T)).rgb;float a=texture2D(s,vec2(uv.x,T+uv.y*T)).r;',
   ' float m=texture2D(s,vec2(uv.x*.5,2.*T+uv.y*T)).r;float d=texture2D(s,vec2(.5+uv.x*.5,2.*T+uv.y*T)).r;',
   ' col=max(col+d*uPaint,0.);',
   ' if(uPl>.5){if(k==0){col=plate(col,uH[0],uL[0]);col=plate(col,uH[1],uL[1]);}else if(k==1){col=plate(col,uH[2],uL[2]);col=plate(col,uH[3],uL[3]);}else{col=plate(col,uH[4],uL[4]);col=plate(col,uH[5],uL[5]);}}',
   ' return dec*(1.-a)+col+m*uLed*uGain;}',
   'void main(){vec2 du=vec2((140.+uv.x*1000.)/1280.,(120.+uv.y*500.)/720.);vec3 dec=mix(texture2D(uD0,du).rgb,texture2D(uD1,du).rgb,uDec);',
   ' vec3 o;if(uO2>=1.)o=car(uI2,dec,2);else if(uO1>=1.){o=car(uI1,dec,1);if(uO2>0.)o=mix(o,car(uI2,dec,2),uO2);}',
   ' else{o=car(uV,dec,0);if(uO1>0.)o=mix(o,car(uI1,dec,1),uO1);if(uO2>0.)o=mix(o,car(uI2,dec,2),uO2);}',
   ' gl_FragColor=vec4(o,1.);}'].join('\n');
  function sh(t,s){var o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);if(!gl.getShaderParameter(o,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(o));return o}
  var pr;try{pr=gl.createProgram();gl.attachShader(pr,sh(gl.VERTEX_SHADER,VS));gl.attachShader(pr,sh(gl.FRAGMENT_SHADER,FS));gl.linkProgram(pr);
    if(!gl.getProgramParameter(pr,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(pr));}catch(e){ST.glError=String(e);return null}
  gl.useProgram(pr);var b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
  var lp=gl.getAttribLocation(pr,'p');gl.enableVertexAttribArray(lp);gl.vertexAttribPointer(lp,2,gl.FLOAT,false,0,0);
  var U={};['uV','uI1','uI2','uD0','uD1','uP','uO1','uO2','uDec','uGain','uPl','uLed','uPaint','uH','uL'].forEach(function(n){U[n]=gl.getUniformLocation(pr,n)});
  function tex(unit){var t=gl.createTexture();gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,t);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([0,0,0,255]));return t}
  var T={V:tex(0),I1:tex(1),I2:tex(2),D0:tex(3),D1:tex(4),P:tex(5)};
  gl.uniform1i(U.uV,0);gl.uniform1i(U.uI1,1);gl.uniform1i(U.uI2,2);gl.uniform1i(U.uD0,3);gl.uniform1i(U.uD1,4);gl.uniform1i(U.uP,5);
  function up(unit,t,src,fmt){gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,t);
    try{gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);gl.texImage2D(gl.TEXTURE_2D,0,fmt||gl.RGB,fmt||gl.RGB,gl.UNSIGNED_BYTE,src);upFail=0;return true}catch(e){ST.glError=String(e);
      /* 3.0 : si le poste refuse d'envoyer les images à la carte graphique (sécurité), on passe en mode léger au lieu d'un rectangle noir */
      if(++upFail>=4){var k='m3dFB';try{if(!sessionStorage.getItem(k)){sessionStorage.setItem(k,'1');lsPut('m3dLite',String(Date.now()));ST.liteReason='refus WebGL';setTimeout(function(){location.reload()},50)}}catch(x){}}return false}}
  var upFail=0;
  try{var dbg=gl.getExtension('WEBGL_debug_renderer_info');ST.renderer=dbg?gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER)}catch(e){}
  var H=new Float32Array(54),L=new Float32Array(6);
  return {gl:gl,U:U,T:T,up:up,H:H,L:L,
    draw:function(s){gl.uniform1f(U.uO1,s.o1);gl.uniform1f(U.uO2,s.o2);gl.uniform1f(U.uDec,s.dec);gl.uniform1f(U.uGain,s.gain);gl.uniform1f(U.uPl,s.pl);
      gl.uniform3fv(U.uLed,s.led);gl.uniform3fv(U.uPaint,s.paint);gl.uniformMatrix3fv(U.uH,false,H);gl.uniform1fv(U.uL,L);gl.drawArrays(gl.TRIANGLE_STRIP,0,4)}};
})();
ST.lite=!gpu;
if(!gpu){/* mode léger : la voiture seule (couche couleur), sur le fond sombre ; pas de décor ni de LED teintées */
  box.classList.add('m3lite');CV.style.display='none';
  VA.style.cssText='position:absolute;left:0;top:0;width:100%;height:300%;opacity:1;pointer-events:none;object-fit:fill';
  [L1,L2].forEach(function(l,i){l.style.cssText='position:absolute;left:0;top:0;width:100%;height:300%;object-fit:fill;pointer-events:none;opacity:0;transition:opacity '+CFG.FADE_MS+'ms;z-index:'+(1+i)})}

/* ---------- homographie : coins de la plaque à l'écran -> coordonnées dans la texture ---------- */
function sq2quad(q){var x0=q[0],y0=q[1],x1=q[2],y1=q[3],x2=q[4],y2=q[5],x3=q[6],y3=q[7];
  var dx1=x1-x2,dx2=x3-x2,dx3=x0-x1+x2-x3,dy1=y1-y2,dy2=y3-y2,dy3=y0-y1+y2-y3,a,b,c,d,e,f,g,h;
  if(Math.abs(dx3)<1e-9&&Math.abs(dy3)<1e-9){a=x1-x0;b=x2-x1;c=x0;d=y1-y0;e=y2-y1;f=y0;g=0;h=0}
  else{var den=dx1*dy2-dx2*dy1;g=(dx3*dy2-dx2*dy3)/den;h=(dx1*dy3-dx3*dy1)/den;a=x1-x0+g*x1;b=x3-x0+h*x3;c=x0;d=y1-y0+g*y1;e=y3-y0+h*y3;f=y0}
  return [a,b,c,d,e,f,g,h,1]}   // (u,v,1) -> écran, rangée par rangée
function inv3(m){var a=m[0],b=m[1],c=m[2],d=m[3],e=m[4],f=m[5],g=m[6],h=m[7],i=m[8];
  var A=e*i-f*h,B=-(d*i-f*g),C=d*h-e*g,det=a*A+b*B+c*C;if(Math.abs(det)<1e-12)return null;
  return [A/det,-(b*i-c*h)/det,(b*f-c*e)/det,B/det,(a*i-c*g)/det,-(a*f-c*d)/det,C/det,-(a*h-b*g)/det,(a*e-b*d)/det]}
function setPlates(slot,list){if(!gpu)return;for(var k=0;k<2;k++){var j=slot*2+k,p=list&&list[k];gpu.L[j]=0;
  if(!p)continue;var M=inv3(sq2quad(p));if(!M)continue;
  /* uniformMatrix3fv attend les colonnes */
  var o=j*9;gpu.H[o]=M[0];gpu.H[o+1]=M[3];gpu.H[o+2]=M[6];gpu.H[o+3]=M[1];gpu.H[o+4]=M[4];gpu.H[o+5]=M[7];gpu.H[o+6]=M[2];gpu.H[o+7]=M[5];gpu.H[o+8]=M[8];gpu.L[j]=p[8]||1}}

/* ============================================================
   lookManager : décor, LED, carrosserie, plaque (suivent le profil, le jour et la nuit)
   ============================================================ */
var lookManager=(function(){
  var cur=null,decCur=null,decImgs={},led=[.12,.43,1],ledFrom=led,ledTo=led,ledT0=0,decT0=0,dec=1,gain=1,paint=[0,0,0],plOn=0,plKey='',plCv=null;
  function hex(h){return [parseInt(h.substr(1,2),16)/255,parseInt(h.substr(3,2),16)/255,parseInt(h.substr(5,2),16)/255]}
  function decor(src,cb){var e=decImgs[src];if(e){if(e.complete&&e.naturalWidth)cb(e);else e.addEventListener('load',function(){cb(e)});return}
    e=new Image();e.decoding='async';e.onload=function(){cb(e)};e.src=src;decImgs[src]=e;
    var ks=Object.keys(decImgs);if(ks.length>3)delete decImgs[ks[0]]}
  function apply(force){
    if(window.M3DV&&M3DV.car)M3DV.car(carId());
    if(!LOOK)return;var s=LOOK.showroom(LIGHT);
    if(s&&(!cur||cur.id!==s.id||force)){var first=!cur;cur=s;
      box.style.backgroundImage='url('+s.img+')';
      ledFrom=led.slice();ledTo=hex(s.led);ledT0=first?0:performance.now();gain=s.gain;
      if(gpu)decor(s.img,function(im){if(cur!==s)return;
        if(first||!decCur){gpu.up(3,gpu.T.D0,im);gpu.up(4,gpu.T.D1,im);dec=1}
        else{/* l'ancien décor passe en D0, le nouveau en D1, fondu de 0 à 1 */
          gpu.up(3,gpu.T.D0,decCur);gpu.up(4,gpu.T.D1,im);dec=0;decT0=performance.now()}
        decCur=im;
        renderer.dirty()})}
    var b=hex(LOOK.BASE_PAINT),t=hex(LOOK.paint()),bm=(b[0]+b[1]+b[2])/3;paint=[(t[0]-b[0])/bm,(t[1]-b[1])/bm,(t[2]-b[2])/bm];
    var k=LOOK.plateStyle()+'|'+LOOK.plateText()+'|'+LOOK.plateDep();
    if(k!==plKey||!plOn){var cv=LOOK.plateCanvas(plCv);if(LOOK.plateStyle()==='masquee'){plOn=0;plKey=k}
      else if(cv&&gpu){plCv=cv;gpu.up(5,gpu.T.P,cv,gpu.gl.RGBA);plOn=1;plKey=k}
      else{plOn=0;setTimeout(function(){apply()},400)}}   // fichiers de plaque pas encore chargés
    renderer.dirty()}
  return {apply:apply,
    state:function(now){var lt=ledT0?Math.min(1,(now-ledT0)/CFG.LOOK_MS):1,e=lt*lt*(3-2*lt);led=[0,1,2].map(function(i){return ledFrom[i]+(ledTo[i]-ledFrom[i])*e});
      if(dec<1&&decT0)dec=Math.min(1,(now-decT0)/CFG.LOOK_MS);
      return {led:led,dec:dec,gain:gain,paint:paint,pl:plOn,busy:lt<1||dec<1}}}})();

/* ============================================================
   renderer : dessine seulement quand quelque chose change
   ============================================================ */
var renderer=(function(){
  var dirty=true,newV=false,lastT=-1,lastF=-1,o1=0,o2=0,f1=null,f2=null,raf=0,frames=0,vframes=0,t0=performance.now(),slow=0;
  var img1=null,img2=null,name1='',name2='';
  /* nouvelle image : repérée par le numéro d'image (requestVideoFrameCallback ne se déclenche pas pour une vidéo invisible) */
  VA.addEventListener('seeked',function(){newV=true;dirty=true;kick()});VA.addEventListener('loadeddata',function(){newV=true;dirty=true;kick()});   /* 3.0 : redessine aussi quand la vidéo est en pause (retour de la vue du dessus) */
  function vidFrame(){return Math.round(VA.currentTime*FPS)%NF}
  function plates(n){if(!PLATES)return null;return PLATES[n]||null}
  function topKey(fi){var s=VA.getAttribute('src')||'';if(/top_d(_j)?\.mp4$/.test(s))return 'u'+Math.max(0,35-fi);if(/top(_j)?\.mp4$/.test(s))return 'u'+Math.min(35,fi);return 'r'+(fi%NF)}
  function anim(o,to,cb){var from=o.v,t=performance.now();o.f=function(now){var k=Math.min(1,(now-t)/CFG.FADE_MS);o.v=from+(to-from)*k;if(k>=1){o.f=null;cb&&cb()}};dirty=true;kick()}
  var A1={v:0,f:null},A2={v:0,f:null};
  function loop(now){raf=0;if(!gpu)return;
    var vis=running&&!document.hidden;if(!vis)return;
    if(A1.f||A2.f)dirty=true;   /* fondu en cours : chaque étape doit être dessinée, pas seulement la première */
    if(A1.f)A1.f(now);if(A2.f)A2.f(now);
    var useV=A2.v<1&&A1.v<1;
    var fi=Math.floor(VA.currentTime*FPS+1e-3);
    if(useV&&VA.readyState>=2&&(newV||fi!==lastF)){if(gpu.up(0,gpu.T.V,VA)){vframes++;lastF=fi;lastT=VA.currentTime;newV=false;setPlates(0,plates(topKey(fi)));dirty=true}}
    var L=lookManager.state(now);
    if(dirty||L.busy){gpu.draw({o1:A1.v,o2:A2.v,dec:L.dec,gain:L.gain,led:L.led,paint:L.paint,pl:L.pl});dirty=false;frames++}
    var dt=now-t0;if(dt>2000){ST.fps=frames*1000/dt;ST.vfps=vframes*1000/dt;
      /* surveillance : rotation en cours et images trop rares -> mode léger (une seule fois, mémorisé) */
      if(mode==='rot'&&!VA.paused&&!dive&&!document.hidden&&VA.readyState>=3&&pageVisible()){if(ST.vfps<CFG.MIN_FPS*VA.playbackRate)slow++;else slow=0;if(slow>=3&&lsGet('m3dLite','0')==='0'){lsPut('m3dLite',String(Date.now()));ST.liteReason='images/s '+ST.vfps.toFixed(1)+' (mode léger au prochain démarrage)'}}
      frames=vframes=0;t0=now}
    if(running&&(!VA.paused||A1.f||A2.f||L.busy||dirty||drag))kick()}
  function kick(){if(!raf&&gpu)raf=requestAnimationFrame(loop)}
  /* images d'états : deux emplacements qui se relaient (comme les deux calques d'avant) */
  var front=0;
  return {
    dirty:function(){dirty=true;kick()},kick:kick,
    show:function(e,name){if(!gpu){var back=front===1?L2:L1,old=front===1?L1:front===2?L2:null;back.src=e.u;back.style.zIndex=3;if(old)old.style.zIndex=2;back.style.opacity=1;front=back===L1?1:2;if(old)setTimeout(function(){if((front===1?L1:L2)!==old)old.style.opacity=0},CFG.FADE_MS+20);return}
      /* le nouvel état monte dans l'emplacement 2, l'ancien descend en 1 */
      if(A2.v>0&&img2){gpu.up(1,gpu.T.I1,img2);name1=name2;setPlates(1,plates(name1));A1.v=1}
      gpu.up(2,gpu.T.I2,e.img);img2=e.img;name2=name;setPlates(2,plates(name));A2.v=0;anim(A2,1,function(){A1.v=0});front=2},
    hide:function(cb){if(!gpu){var f=front===1?L1:front===2?L2:null;if(f){f.style.opacity=0;setTimeout(function(){front=0;cb&&cb()},CFG.FADE_MS)}else cb&&cb();return}
      /* aussi quand l'état vient d'être demandé et n'a pas encore commencé son fondu (sinon il resterait affiché) */
      if(A2.v>0||A1.v>0||A2.f){newV=true;A1.v=0;anim(A2,0,function(){front=0;cb&&cb()})}else cb&&cb()},
    shown:function(){return front?(gpu?name2:((front===1?L1:L2).getAttribute('src')||'')):''},
    snapshot:function(){return true},
    state:function(){return {o1:A1.v,o2:A2.v,lastF:lastF,raf:!!raf,front:front,name2:name2}}}})();

/* ---------- memoryManager : cache d'images limité (LRU) ---------- */
var memoryManager=(function(){
  var C=new Map(),PIN={};
  function url(n){return CARS[CAR].dir+'pn/'+n+(LIGHT?'_j':'')+'.webp'}
  function get(n){var u=url(n),e=C.get(u);
    if(e){C.delete(u);C.set(u,e);return e}
    var i=new Image();i.decoding='async';e={u:u,img:i,ready:false,p:null};
    e.p=new Promise(function(res){i.onload=function(){(i.decode?i.decode():Promise.resolve()).then(function(){e.ready=true;res(e)},function(){e.ready=true;res(e)})};i.onerror=function(){res(e)}});
    i.src=u;C.set(u,e);trim();ST.imgs=C.size;return e}
  function trim(){var it=C.keys();while(C.size>CFG.CACHE_MAX){var k=it.next().value;if(!k)break;if(PIN[k]){var v=C.get(k);C.delete(k);C.set(k,v);continue}var o=C.get(k);C.delete(k);try{o.img.src=''}catch(e){}}}
  function pin(names){PIN={};names.forEach(function(n){PIN[url(n)]=1;get(n)})}
  function clear(){C.forEach(function(o){try{o.img.src=''}catch(e){}});C.clear();PIN={};ST.imgs=0}
  return {get:get,pin:pin,clear:clear,url:url}})();

/* ---------- vehicleStateManager : état des portes, phares, warnings ---------- */
var vehicleStateManager=(function(){
  var lsT=0,auto=true,warnLS=false;
  function readLS(){var n=Date.now();if(n-lsT<1000)return;lsT=n;auto=lsGet('m3dAuto','1')!=='0';warnLS=lsGet('m3dWarn','0')==='1'}
  return {
    mask:function(){if(window.__m3dExp)return 63&~noMask();var o={};try{o=window.__dOpen?window.__dOpen():{}}catch(e){}var m=0;K.forEach(function(k,b){if(o[k])m|=1<<b});m&=~noMask();return m},
    warn:function(){readLS();return warnLS||!!(window.__carv&&window.__carv.warn===true)},
    lights:function(){var e=document.getElementById('lights');return !!(e&&e.classList.contains('on'))},
    auto:function(){readLS();return auto}}})();

/* ---------- cameraManager : choisit la vue qui montre le mieux ce qui est ouvert ---------- */
var cameraManager={
  viewFor:function(m){if(m&&CARS[CAR]&&CARS[CAR].top)return 'T';var l=m&LEFT,r=m&RIGHT;if(l&&r)return 'T';if(l)return 'G';return 'R'},
  name:function(v,m){var s=(m<10?'0':'')+m;return VIEW[v].p+s},
  neighbours:function(m){var v=this.viewFor(m),out=[];for(var b=0;b<6;b++){if(noMask()&(1<<b))continue;var n=m^(1<<b);out.push(this.name(this.viewFor(n),n))}return out}};

/* ---------- videoManager : une seule vidéo décodée à la fois ---------- */
var videoManager=(function(){
  function frame(){return Math.round(VA.currentTime*FPS)%NF}
  function seek(f){try{VA.currentTime=((f%NF)+NF)%NF/FPS}catch(e){}}
  /* change la source sans écran noir : en WebGL la dernière image reste affichée tant que la nouvelle n'est pas prête */
  function swap(src,cb){var t=VA.currentTime,pl=!VA.paused;VA.pause();
    var f=function(){VA.removeEventListener('loadeddata',f);var sk=function(){VA.removeEventListener('seeked',sk);if(pl&&mode==='rot'&&running)rotationManager.play(true);renderer.dirty();cb&&cb()};VA.addEventListener('seeked',sk);try{VA.currentTime=t}catch(e){sk()}};
    VA.addEventListener('loadeddata',f);VA.src=src;VA.load()}
  return {frame:frame,seek:seek,swap:swap}})();

/* ---------- rotationManager : rotation lente, régulière, sans à-coup ---------- */
var rotationManager=(function(){
  var rampT=0;
  function setRate(r){try{VA.playbackRate=r}catch(e){}}
  function play(soft){if(!running||mode!=='rot'||dive)return;clearTimeout(rampT);
    if(soft&&VA.paused){var s=[0.3,0.5,0.7,0.85,1],i=0;setRate(CFG.ROT_SPEED*s[0]);VA.play().catch(function(){});
      rampT=setTimeout(function st(){if(++i>=s.length||VA.paused)return;setRate(CFG.ROT_SPEED*s[i]);rampT=setTimeout(st,CFG.EASE_MS/s.length)},CFG.EASE_MS/s.length)}
    else{setRate(CFG.ROT_SPEED);if(VA.paused)VA.play().catch(function(){})}
    renderer.kick()}
  function stop(){clearTimeout(rampT);if(!VA.paused)VA.pause()}
  return {play:play,stop:stop}})();

/* ---------- doorAnimationManager : réaction immédiate, fondu court ---------- */
var doorAnimationManager=(function(){
  var want=null;
  function show(name,t0){var e=memoryManager.get(name);want=e.u;
    var go=function(){if(want!==e.u)return;renderer.show(e,name);
      if(t0){var ms=Math.round(performance.now()-t0);ST.doorMs.push(ms);if(ST.doorMs.length>30)ST.doorMs.shift();ST.lastDoorMs=ms}};
    if(e.ready)go();else e.p.then(go)}
  function hide(cb){want=null;renderer.hide(cb)}
  return {show:show,hide:hide}})();

/* 3.0 : quand la vue du dessus s'ouvre depuis la rotation, la caméra monte au-dessus du toit (vidéo top.mp4, 36 images) */
/* 3.0 : vue aérienne des portes. Voiture et plateau ne bougent jamais : seule la caméra (pré-calculée) s'élève,
   pivote et finit à la verticale (top.mp4, 36 images), puis redescend par le même chemin (top_d.mp4).
   La montée part de l'angle de la vue posée (image POSE) : si le plateau tournait, il finit d'abord son tour en douceur jusqu'à cet angle. */
var climbing=false,CF=POSE;
function isTopSrc(){return /top(_j)?\.mp4$/.test(VA.getAttribute('src')||'')}
function settle(done){var cur=videoManager.frame(),d=((CF-cur)%NF+NF)%NF;if(d>NF/2)d-=NF;
  if(Math.abs(d)<=1||VA.readyState<2){done();return}
  var dur=Math.max(350,Math.min(1200,Math.abs(d)/NF*2600)),t0=performance.now();
  (function st(){var k=Math.min(1,(performance.now()-t0)/dur),e=k*k*(3-2*k);videoManager.seek(Math.round(cur+d*e));
    if(k<1)requestAnimationFrame(st);else setTimeout(done,60)})()}
function playSeg(file,done){var fin=false;
  function end(){if(fin)return;fin=true;VA.removeEventListener('ended',end);done()}
  var go=function(){VA.removeEventListener('loadeddata',go);VA.loop=false;try{VA.currentTime=0}catch(e){}VA.addEventListener('ended',end);
    try{VA.playbackRate=1}catch(e){}var pr=VA.play();if(pr&&pr.catch)pr.catch(end);renderer.kick()};
  VA.addEventListener('loadeddata',go);VA.src=CARS[CAR].dir+file;VA.load();
  setTimeout(end,6000)}   /* garde-fou : on ne reste jamais bloqué entre deux vues */
function climbUp(done){var c=CARS[CAR];if(!c||!c.top){done();return}
  climbing=true;rotationManager.stop();
  doorAnimationManager.hide();   /* l'image fixe (voiture posée) laisse voir la caméra */
  settle(function(){document.body.classList.add('m3dTop');playSeg(LIGHT?'top_j.mp4':'top.mp4',function(){climbing=false;done()})})}
function climbDown(done){
  climbing=true;doorAnimationManager.hide();
  playSeg(LIGHT?'top_d_j.mp4':'top_d.mp4',function(){var lit=vehicleStateManager.lights();litSrc=lit;litL=LIGHT;VA.loop=true;
    videoManager.swap(rotSrc(lit),function(){videoManager.seek(CF);climbing=false;document.body.classList.remove('m3dTop');done()})})}
function staticName(){var bl=vehicleStateManager.warn()&&(Math.floor(Date.now()/450)%2===0),hl=vehicleStateManager.lights();
  if(mask)return cameraManager.name(view,mask);return bl&&hl?'dlw':bl?'dw':hl?'dl':'c00'}
function pageVisible(){var st=document.getElementById('stage'),pg=st&&st.getAttribute('data-page');return pg==='home'||document.body.classList.contains('persOn')}

/* ---------- boucle d'état (légère : ne fait rien si rien ne change) ---------- */
function tick(){
  var bg=document.body.classList.contains('inBg');
  ST.videosActive=(VA.paused?0:1)+(VD.paused?0:1);
  if(bg||!running){rotationManager.stop();return}
  if(dive||climbing)return;
  var m=vehicleStateManager.mask(),w=vehicleStateManager.warn();
  document.body.classList.toggle('m3dTop',(mode==='door'&&view==='T')||/top(_d)?(_j)?\.mp4$/.test(VA.getAttribute('src')||''));   /* panneau caché jusqu'à la fin de la descente */
  if(mode==='rot'){
    if(m||w){var t0=performance.now();rotationManager.stop();mode='door';mask=m;view=cameraManager.viewFor(m);closeT=0;
      if(view==='T'&&CARS[CAR].top){memoryManager.pin([cameraManager.name('T',m),'t00']);climbUp(function(){if(mode==='door'&&mask)doorAnimationManager.show(staticName(),t0)});memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(m)));return}
      doorAnimationManager.show(staticName(),t0);memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(m)));return}
    var lit=vehicleStateManager.lights();var want=rotSrc(lit);if(VA.getAttribute('src')!==want){var wasTop=/top(_j)?\.mp4$/.test(VA.getAttribute('src')||'');litSrc=lit;litL=LIGHT;VA.loop=true;videoManager.swap(want,wasTop?function(){videoManager.seek(CF)}:null)}
    var vis=pageVisible();
    if(!vis||drag||Date.now()<hold){if(!drag)rotationManager.stop()}
    else if(!vehicleStateManager.auto()){if(!posed){posed=true;videoManager.seek(POSE)}rotationManager.stop()}
    else if(VA.paused){posed=false;rotationManager.play(true)}
    knob.ensure();
  }else{
    var top=isTopSrc();
    if(m!==mask){var t1=performance.now();mask=m;closeT=0;if(m)view=cameraManager.viewFor(m);else view=top?'T':'R';
      if(m&&top&&view!=='T'){view='T'}
      if(m||(w&&!top)){doorAnimationManager.show(staticName(),t1);memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(m)))}
      else if(top)doorAnimationManager.hide();   /* toutes fermées : la vidéo, arrêtée sur la vue du dessus portes fermées, reprend la main */
      return}
    if(!m&&(!w||top)){
      if(!closeT){closeT=Date.now()+CFG.CLOSE_MS;doorAnimationManager.hide()}
      else if(Date.now()>closeT){closeT=0;
        var back=function(){mode='rot';view='R';posed=false;if(pageVisible()&&vehicleStateManager.auto())rotationManager.play(true)};
        if(top)climbDown(back);else back()}
      return}
    var n=staticName();if(renderer.shown()!==n&&renderer.shown()!==memoryManager.url(n))doorAnimationManager.show(n);
  }
}
setInterval(tick,60);

/* ---------- curseur de rotation sous la voiture ---------- */
var knob=(function(){var SL=document.createElement('div');SL.id='m3sl';SL.innerHTML='<i class="tr"></i><b class="kn"><svg viewBox="0 0 24 24"><path d="M9 7l-5 5 5 5M15 7l5 5-5 5"/></svg></b>';(document.getElementById('stage')||V.parentNode).appendChild(SL);
  var KN=SL.querySelector('.kn'),sd=null,on=false;function place(f,big){KN.style.transform='translateX('+(f*340).toFixed(1)+'px)'+(big?' scale(1.15)':'')}
  function need(){return running&&mode==='rot'&&!dive&&!sd&&pageVisible()&&!document.body.classList.contains('inBg')}
  function loop(){if(!need()){on=false;return}place((VA.currentTime*FPS%NF)/NF);requestAnimationFrame(loop)}
  KN.style.left='0';
  function at(e){var r=SL.getBoundingClientRect();return Math.max(0,Math.min(.999,(e.clientX-r.left)/r.width))}
  SL.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive)return;e.preventDefault();e.stopPropagation();SL.setPointerCapture(e.pointerId);rotationManager.stop();posed=true;window.__m3dDrag=true;window.__m3dDragAt=Date.now();sd=1;SL.classList.add('on');var f=at(e);place(f,1);VA.currentTime=f*DUR});
  SL.addEventListener('pointermove',function(e){if(!sd)return;e.stopPropagation();var f=at(e);place(f,1);var t=f*DUR;if(Math.abs(t-VA.currentTime)>0.02)VA.currentTime=t});
  function end(){if(!sd)return;sd=null;SL.classList.remove('on');hold=Date.now()+CFG.RESUME_MS;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450);knob.ensure()}
  SL.addEventListener('pointerup',end);SL.addEventListener('pointercancel',end);
  return {ensure:function(){if(!on&&need()){on=true;requestAnimationFrame(loop)}}}})();

/* ---------- rotation au doigt sur l'accueil ---------- */
function inCar(e){var st=document.getElementById('stage');if(!st)return false;var r=st.getBoundingClientRect(),sc=r.width/1280,x=(e.clientX-r.left)/sc,y=(e.clientY-r.top)/sc;return x>230&&x<1050&&y>150&&y<560}
document.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit')||document.body.classList.contains('persOn'))return;
  if(e.target.closest&&e.target.closest('.gc,.tile,button,#tiles,#top,#m3btn,#m3ctl,.cw,#m3sl'))return;if(!inCar(e))return;
  rotationManager.stop();posed=true;drag={x:e.clientX,t:VA.currentTime};window.__m3dDrag=true;window.__m3dDragAt=Date.now();renderer.kick()},true);
document.addEventListener('pointermove',function(e){if(!drag)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280;
  var t=drag.t+(e.clientX-drag.x)/sc*CFG.DRAG_K;t=((t%DUR)+DUR)%DUR;if(Math.abs(t-VA.currentTime)>0.02)VA.currentTime=t},true);
function up(){if(drag){drag=null;hold=Date.now()+CFG.RESUME_MS;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450)}}
document.addEventListener('pointerup',up,true);document.addEventListener('pointercancel',up,true);

/* ---------- plongée dans l'habitacle (onglet Véhicule) ---------- */
function releaseDive(){try{VD.pause();VD.removeAttribute('src');VD.load()}catch(e){}}
function diveSrc(on){return CARS[CAR].dir+(LIGHT?(on?'dive_j.mp4':'dive_r_j.mp4'):(on?'dive.mp4':'dive_r.mp4'))}
function diveTo(on,cb){if(!CARS[CAR].dive){cb&&cb();return}if(on&&dive===1||!on&&dive===0){cb&&cb();return}
  if(on){window.__m3dExp=false;if(mode!=='rot'){mode='rot';mask=0;doorAnimationManager.hide()}}
  rotationManager.stop();dive=2;VD.src=diveSrc(on);VD.style.display='block';VD.style.opacity=0;
  var started=function(){VD.onplaying=null;VD.style.opacity=1;if(on)V.style.opacity=0};VD.onplaying=started;VD.play().catch(function(){started()});
  VD.onended=function(){dive=on?1:0;if(!on){videoManager.seek(DS);V.style.opacity=1;VD.style.opacity=0;setTimeout(function(){VD.style.display='none';releaseDive()},CFG.FADE_MS);posed=false}
    document.body.classList.toggle('m3dIn',on);cb&&cb()}}
new MutationObserver(function(){var pg=document.getElementById('stage').getAttribute('data-page');if(!running)return;if(pg==='veh')diveTo(true);else if(dive===1&&!window.__m3dInside)diveTo(false);knob.ensure();renderer.dirty()}).observe(document.getElementById('stage'),{attributes:true,attributeFilter:['data-page']});

/* ---------- interface publique (inchangée, + look) ---------- */
window.M3DV={
  start:function(){if(running||!hasCar())return;running=true;lookManager.apply(true);memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(0)));
    litSrc=vehicleStateManager.lights();if(VA.getAttribute('src')!==rotSrc(litSrc)){litL=LIGHT;VA.src=rotSrc(litSrc);videoManager.seek(VIEW.R.f)}
    if(VA.readyState<2){doorAnimationManager.show('c00');var sw=function(){VA.removeEventListener('playing',sw);VA.removeEventListener('seeked',sw);if(running&&mode==='rot'&&!dive)doorAnimationManager.hide()};VA.addEventListener('playing',sw);VA.addEventListener('seeked',sw);VA.play().catch(function(){})}   /* 3.0 : aussi quand la voiture est posée (rotation coupée) : sinon l'image d'attente restait affichée */
    renderer.kick();knob.ensure()},
  stop:function(){running=false;rotationManager.stop();if(dive){dive=0;VD.style.display='none';V.style.opacity=1;document.body.classList.remove('m3dIn');releaseDive()}},
  setColor:function(){lookManager.apply()},
  look:function(){lookManager.apply()},
  car:function(id){if(!CARS[id]||id===CAR)return CAR;CAR=id;memoryManager.clear();PLATES=null;loadPlates();
    if(running){memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(mask)));if(mode!=='rot'&&!dive)doorAnimationManager.show(staticName());renderer.dirty()}return CAR},
  cars:function(){return Object.keys(CARS)},hasCar:hasCar,
  theme:function(light){light=!!light;if(light===LIGHT)return;LIGHT=light;lookManager.apply();
    if(running){memoryManager.pin(['c00','dw','dl','dlw'].concat(cameraManager.neighbours(mask)));if(mode!=='rot'&&!dive)doorAnimationManager.show(staticName());renderer.dirty()}},
  explode:function(){window.__m3dExp=!window.__m3dExp;return window.__m3dExp},
  inside:function(){var on=dive!==1;window.__m3dInside=on;diveTo(on);return on},
  lite:function(on){lsPut('m3dLite',on?'1':'0');return 'redémarre Cockpit pour appliquer'},
  cfg:CFG};
document.addEventListener('visibilitychange',function(){if(document.hidden)rotationManager.stop();else renderer.dirty()});
window.__m3dDbg=function(){return Object.assign({mode:mode,mask:mask,dive:dive,running:running,gpu:!!gpu},renderer.state())};
window.__m3dOk=1;
})();
