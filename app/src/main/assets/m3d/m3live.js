/* Cockpit 3.6 : voiture 3D en direct (plus aucune vidéo de voiture).
   La vraie voiture (car.glb préparé par le kit) est dessinée par la carte graphique : rotation, portes, phares,
   warnings, couleur de carrosserie, LED de la plateforme, vue du dessus et habitacle se calculent à la volée.
   Le décor (showroom) est dessiné dans le même canvas, opaque : pas de transparence WebGL (carré noir des puces PowerVR).
   Interface publique M3DV inchangée. Le moteur ne dessine que si quelque chose bouge, 30 images/s au plus,
   et baisse sa qualité tout seul si le poste peine. */
(function(){
var box=document.getElementById('m3d');if(!box||!window.C3D)return;
var C=window.C3D,T=C.THREE;

/* ---------- Réglages ---------- */
var CFG=Object.assign({
  TURN_S:32,        // durée d'un tour de plateau (s)
  ROT_SPEED:1,      // 1 = un tour en TURN_S secondes
  EASE_MS:900,      // démarrage progressif de la rotation
  RESUME_MS:2500,   // reprise de la rotation après un geste du doigt
  CLOSE_MS:1200,    // retour à la rotation après la fermeture des portes
  DOOR_MS:900,      // ouverture / fermeture d'une porte
  CAM_MS:1300,      // montée vers la vue du dessus, plongée dans l'habitacle
  LOOK_MS:600,      // glissement de couleur (LED, carrosserie) et fondu de décor
  DRAG_K:0.0141,    // rotation au doigt (radians par pixel)
  FPS_CAP:30,       // images par seconde au plus
  MIN_FPS:26        // en dessous, la qualité baisse d'un cran (mémorisé)
},window.__m3dCfg||{});
var POSE=0.75,TOPANG=Math.PI/2,K=['d1','d2','d3a','d3b','d4','d5'];
var LOOK=window.CockpitLook;
function lsGet(k,d){try{var v=localStorage.getItem(k);return v===null?d:v}catch(e){return d}}
function lsPut(k,v){try{localStorage.setItem(k,v)}catch(e){}}
var LIGHT=document.body.classList.contains('clair');
var ST=window.__m3dStats={fps:0,renderer:'',q:0,tris:0,car:'',loadMs:0,err:''};
function carId(){try{return LOOK&&LOOK.car?LOOK.car():null}catch(e){return null}}
function cdir(c){try{if(window.Android&&Android.carStatus){var s=JSON.parse(Android.carStatus(c)||'{}');if(s&&s.installed&&s.path)return s.path}}catch(e){}return 'm3d/cars/'+c+'/'}
function xhr(url,type,ok,ko){var x=new XMLHttpRequest();x.open('GET',url);x.responseType=type;x.onload=function(){if(x.status===200||(x.status===0&&x.response))ok(x.response);else ko&&ko('HTTP '+x.status)};x.onerror=function(){ko&&ko('lecture impossible')};x.send()}
function clamp(v,a,b){return v<a?a:v>b?b:v}
function smooth(k){k=clamp(k,0,1);return k*k*(3-2*k)}
function ease(x){return 1-Math.pow(1-x,3)}
function wrap(a){a%=2*Math.PI;return a<0?a+2*Math.PI:a}
function short(from,to){var d=wrap(to-from);return d>Math.PI?d-2*Math.PI:d}

/* ---------- Qualité (baisse toute seule si le poste peine, mémorisée 7 jours) ---------- */
var QL=[{pr:1,sh:0},{pr:.85,sh:0},{pr:.75,sh:0},{pr:.6,sh:0}];   /* 3.6.2 : plus d'ombre calculée (l'ombre douce « ao » suffit) */
var GPU=(function(){try{var c=document.createElement('canvas'),g=c.getContext('webgl');if(!g)return '';var x=g.getExtension('WEBGL_debug_renderer_info'),r=x?g.getParameter(x.UNMASKED_RENDERER_WEBGL):g.getParameter(g.RENDERER);var l=g.getExtension('WEBGL_lose_context');if(l)l.loseContext();return String(r||'')}catch(e){return ''}})();
var SOFT=/swiftshader|llvmpipe|softpipe|software/i.test(GPU);
var WEAK=SOFT||/powervr|imagination|ge8\d{3}|mali-(4[05]0|t[6-8]\d\d|g31|g51|g52|g57|g[4-7]\d\b)|mali-[gt]?[4-7]\d|vivante|\bgc\d{3,4}|adreno \(tm\) ([3-5]\d\d|61\d)/i.test(GPU);
var QKEY='m3dQ:'+GPU.replace(/\W+/g,'').slice(0,40);
var qi=(function(){var n=parseInt(String(lsGet(QKEY,'0')).split('|')[0],10)||0;n=clamp(n,0,QL.length-1);if(WEAK||window.__lite)n=Math.max(n,2);if(SOFT)n=QL.length-1;return n})();
ST.gpu=GPU;ST.weak=WEAK;
ST.q=qi;

/* ---------- Éléments ---------- */
box.insertAdjacentHTML('beforeend','<div id="m3v" style="visibility:hidden;pointer-events:none"></div><canvas id="m3gl"></canvas>');
var V=document.getElementById('m3v'),CV=document.getElementById('m3gl');
/* 3.6.2 : quand la 3D est affichée (canvas opaque), les couches cachées dessous ne sont plus dessinées */
function cvOp(v){CV.style.opacity=v;try{document.body.classList.toggle('m3dLive',v===1)}catch(e){}}
CV.style.cssText='position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:1;opacity:0;transition:opacity .45s';

var R=null;try{R=new T.WebGLRenderer({canvas:CV,alpha:false,antialias:!WEAK,powerPreference:'high-performance',preserveDrawingBuffer:false})}catch(e){ST.err='WebGL indisponible : '+e}
if(!R){/* sans carte graphique : l'accueil reste en 2D, rien ne casse */
  window.M3DV={start:function(){},stop:function(){},free:function(){},setColor:function(){},look:function(){},car:function(){return null},cars:function(){return []},hasCar:function(){return null},theme:function(){},explode:function(){return false},inside:function(){return false},lite:function(){return qi>=2?'1':''},opens:function(){return []},cfg:CFG};
  window.__m3dOk=1;return}
try{var gl0=R.getContext(),dx=gl0.getExtension('WEBGL_debug_renderer_info');ST.renderer=dx?gl0.getParameter(dx.UNMASKED_RENDERER_WEBGL):gl0.getParameter(gl0.RENDERER)}catch(e){}
R.setPixelRatio(1);R.outputColorSpace=T.SRGBColorSpace;R.toneMapping=T.ACESFilmicToneMapping;R.toneMappingExposure=1.4;R.autoClear=false;
R.shadowMap.type=T.PCFSoftShadowMap;R.shadowMap.enabled=QL[qi].sh>0;

/* décor : deux plans en fondu, cadrés comme le fond de la page (center/cover) */
var BS=new T.Scene(),BC=new T.OrthographicCamera(-1,1,1,-1,0,1);
function bgPlane(){var m=new T.Mesh(new T.PlaneGeometry(2,2),new T.MeshBasicMaterial({transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false}));BS.add(m);return m}
var BG0=bgPlane(),BG1=bgPlane();BG1.renderOrder=1;
function bgVis(){BG0.visible=BG0.material.opacity>.001;BG1.visible=BG1.material.opacity>.001}

/* scène : même éclairage que le kit */
var S=new T.Scene();var pm=new T.PMREMGenerator(R);S.environment=pm.fromScene(new C.RoomEnvironment(),.04).texture;pm.dispose();
/* 3.6.1 : éclairage d'appoint (certaines puces gèrent mal le reflet d'environnement : la voiture paraissait noire) */
S.add(new T.HemisphereLight(0xffffff,0x3a3f4a,1.1));S.add(new T.AmbientLight(0xffffff,.35));
var FL=new T.DirectionalLight(0xffffff,1.2);FL.position.set(2,4,8);S.add(FL);var BL=new T.DirectionalLight(0xffffff,.8);BL.position.set(-3,3,-7);S.add(BL);
var cam=new T.PerspectiveCamera(28,1280/720,.05,100);
var DL=new T.DirectionalLight(0xffffff,2.0);DL.position.set(0.4,10,0.8);DL.castShadow=QL[qi].sh>0;DL.shadow.mapSize.set(1024,1024);DL.shadow.radius=4;var sc=DL.shadow.camera;sc.left=-4;sc.right=4;sc.top=4;sc.bottom=-4;S.add(DL);
var b1=new T.PointLight(0xffffff,6,10);b1.position.set(-3,.4,1);S.add(b1);var b2=new T.PointLight(0xffffff,6,10);b2.position.set(3,.4,-1);S.add(b2);
var cs=(function(){var c=document.createElement('canvas');c.width=c.height=128;var x=c.getContext('2d'),g=x.createRadialGradient(64,64,0,64,64,64);g.addColorStop(0,'rgba(0,0,0,1)');g.addColorStop(.6,'rgba(0,0,0,.75)');g.addColorStop(1,'rgba(0,0,0,0)');x.fillStyle=g;x.fillRect(0,0,128,128);return new T.CanvasTexture(c)})();
var ao=new T.Mesh(new T.PlaneGeometry(2.3,4.7),new T.MeshBasicMaterial({map:cs,transparent:true,depthWrite:false}));ao.rotation.x=-Math.PI/2;ao.position.y=.003;ao.scale.set(1.05,1,1.02);
var turn=new T.Group();S.add(turn);turn.add(ao);
var cab=new T.PointLight(0xffffff,0,3,1.5);cab.position.set(.36,1.3,0);cab.visible=false;S.add(cab);
/* 3.6.2 : une lumière éteinte coûte quand même (three.js la compte) : on la retire du calcul ; sur poste faible, la lumière arrière part et l'avant compense */
function lightsQ(){BL.visible=qi<2;FL.intensity=qi<2?1.2:1.55}lightsQ();

/* ---------- plateforme (une fois) ---------- */
var plat=null,ptN=null,ptJ=null,platMats=[],PLAT_Y=-.002;
function loadPlat(done){if(plat){done();return}
  xhr('m3d/plat/plat.glb','arraybuffer',function(buf){new C.GLTFLoader().parse(buf,'',function(g){var p=g.scene;var pb=new T.Box3().setFromObject(p),ps=pb.getSize(new T.Vector3());var sx=6.1/ps.x,sy=.21/ps.y;p.scale.set(sx,sy,sx);
    pb=new T.Box3().setFromObject(p);var pc=pb.getCenter(new T.Vector3());p.position.set(-pc.x,-pb.max.y-0.002,-pc.z);
    var TL=new T.TextureLoader(),n=0;function tx(u,cb){TL.load(u,function(t){t.flipY=false;t.colorSpace=T.SRGBColorSpace;cb(t);if(++n>=3)done()},undefined,function(){if(++n>=3)done()})}
    p.traverse(function(o){if(!o.isMesh)return;o.receiveShadow=true;var mt=o.material;mt.envMapIntensity=.7;mt.roughness=.35;mt.metalness=.3;mt.emissive=new T.Color(1,1,1);mt.emissiveIntensity=.85;platMats.push(mt)});
    tx('m3d/plat/base.jpg',function(t){ptN=t;platMats.forEach(function(m){m.map=LIGHT&&ptJ?ptJ:t;m.needsUpdate=true})});
    tx('m3d/plat/emis.jpg',function(t){platMats.forEach(function(m){m.emissiveMap=t;m.needsUpdate=true})});
    tx('m3d/plat/day.jpg',function(t){ptJ=t;if(LIGHT)platMats.forEach(function(m){m.map=t;m.needsUpdate=true})});
    turn.add(p);plat=p;try{p.updateMatrixWorld(true);var rc=new T.Raycaster(new T.Vector3(1.2,5,0),new T.Vector3(0,-1,0)),h=rc.intersectObject(p,true)[0];if(h&&h.point.y<.05&&h.point.y>-.2){PLAT_Y=h.point.y;ao.position.y=PLAT_Y+.003}}catch(e){}},function(e){ST.err='plateforme : '+e;done()})},function(e){ST.err='plateforme : '+e;done()})}

/* ---------- voiture ---------- */
var CAR=null,carObj=null,parts={},LT={head:[],tail:[],sig:[]},paintMats=[],FICHE={},loadingCar=null;
function dispose(o){o.traverse(function(q){if(q.geometry)q.geometry.dispose();if(q.material)(Array.isArray(q.material)?q.material:[q.material]).forEach(function(m){for(var k in m){var v=m[k];if(v&&v.isTexture)v.dispose()}m.dispose()})})}
function loadCar(id){if(!id||id===CAR&&carObj||loadingCar===id)return;loadingCar=id;var t0=performance.now(),dir=cdir(id);cvOp(0);
  xhr(dir+'car.json','text',function(t){try{FICHE=JSON.parse(t)||{}}catch(e){FICHE={}}},function(){FICHE={}});
  xhr(dir+'car.glb','arraybuffer',function(buf){if(loadingCar!==id)return;
    new C.GLTFLoader().parse(buf,'',function(g){if(loadingCar!==id){dispose(g.scene);return}
      if(carObj){turn.remove(carObj);dispose(carObj)}
      carObj=g.scene;parts={};LT={head:[],tail:[],sig:[]};paintMats=[];var tris=0;
      carObj.traverse(function(o){var u=o.userData||{};
        if(u.part&&K.indexOf(u.part)>=0)parts[u.part]={p:o,axis:u.axis==='x'?'x':'y',max:+u.max||0,v:0,from:0,to:0,t0:0};
        if(!o.isMesh)return;o.castShadow=true;var g2=o.geometry;tris+=(g2.index?g2.index.count:g2.attributes.position.count)/3;
        (Array.isArray(o.material)?o.material:[o.material]).forEach(function(q){if(!q||q.__fix)return;q.__fix=1;
          if(q.metalness!==undefined&&!q.metalnessMap){q.metalness=Math.min(q.metalness,u.paint?.45:.6)}if(q.roughness!==undefined&&!q.roughnessMap)q.roughness=Math.max(q.roughness,u.paint?.22:.15);if(q.envMapIntensity!==undefined)q.envMapIntensity=1.3});
        var mt=o.material;if(Array.isArray(mt))return;
        if(u.paint&&paintMats.indexOf(mt)<0)paintMats.push(mt);
        if(u.light&&LT[u.light]&&LT[u.light].indexOf(mt)<0)LT[u.light].push(mt);
        if(u.glass)o.castShadow=false});
      try{ground(carObj)}catch(e){}
      try{var t1=performance.now();sanitize(carObj,WEAK||!!window.__lite);var dc0=0;carObj.traverse(function(o){if(o.isMesh)dc0++});shareAndMerge(carObj);var dc1=0;carObj.traverse(function(o){if(o.isMesh)dc1++});ST.meshes=dc0+'→'+dc1;ST.mergeMs=Math.round(performance.now()-t1)}catch(e){ST.err='fusion : '+e}
      turn.add(carObj);CAR=id;loadingCar=null;ST.car=id;ST.tris=Math.round(tris);ST.loadMs=Math.round(performance.now()-t0);
      look.paintNow();doors.reset();lights();resize();dirty();setTimeout(function(){if(running)cvOp(1)},60)},
      function(e){loadingCar=null;ST.err='voiture : '+e})},
    function(e){loadingCar=null;ST.err='voiture : '+e})}

/* ---------- 3.6.2 : allègement au chargement (vaut pour toutes les voitures, sans refaire les packs) ---------- */
function sanitize(root,faible){var MAXTEX=faible?512:1024,
  CLES=['map','normalMap','roughnessMap','metalnessMap','emissiveMap','aoMap','alphaMap','clearcoatMap','clearcoatNormalMap','clearcoatRoughnessMap','specularIntensityMap','specularColorMap'];
  root.traverse(function(o){if(!o.isMesh)return;
    (Array.isArray(o.material)?o.material:[o.material]).forEach(function(m){if(!m||m.__san)return;m.__san=1;
      /* verre « transmission » : three.js redessine toute la scène une 2e fois à chaque image → verre simple */
      if(m.transmission>0){m.transmission=0;m.thickness=0;m.transparent=true;m.opacity=Math.min(m.opacity,.35);m.depthWrite=false}
      /* transparent + double face = 2 passes et un recalcul du programme à chaque image : une seule passe */
      if(m.transparent){m.forceSinglePass=true;m.depthWrite=false}
      if(faible){if(m.clearcoat)m.clearcoat=0;if(m.sheen)m.sheen=0;if(m.iridescence)m.iridescence=0}
      CLES.forEach(function(k){var t=m[k];if(!t||!t.image||t.__small)return;t.__small=1;var im=t.image,w=im.width,h=im.height,sc=Math.min(1,MAXTEX/Math.max(w,h));if(!(sc<1))return;
        var c=document.createElement('canvas');c.width=Math.max(1,Math.round(w*sc));c.height=Math.max(1,Math.round(h*sc));c.getContext('2d').drawImage(im,0,0,c.width,c.height);if(im.close)try{im.close()}catch(e){}t.image=c;t.needsUpdate=true})})})}
function shareAndMerge(root){
  /* 1) matériaux identiques → un seul (peinture, feux et vitres restent séparés par type) */
  var cache={};function tid(t){return t?t.uuid:''}
  function key(m,u){return [m.type,m.color&&m.color.getHex(),m.metalness,m.roughness,m.transparent,m.opacity,m.side,m.alphaTest,m.vertexColors,m.emissive&&m.emissive.getHex(),m.emissiveIntensity,
    m.clearcoat||0,m.transmission||0,m.specularIntensity,m.ior,tid(m.map),tid(m.normalMap),tid(m.roughnessMap),tid(m.metalnessMap),tid(m.emissiveMap),tid(m.aoMap),tid(m.alphaMap),tid(m.clearcoatNormalMap),
    u.paint?'P':'',u.light||'',u.glass?'G':''].join('|')}
  root.traverse(function(o){if(!o.isMesh||Array.isArray(o.material))return;var k=key(o.material,o.userData||{});if(cache[k]&&cache[k]!==o.material)o.material=cache[k];else cache[k]=o.material});
  /* 2) maillages fixes qui partagent un matériau → un seul (les portes, capot, coffre ne sont pas touchés) */
  root.updateMatrixWorld(true);var inv=new T.Matrix4().copy(root.matrixWorld).invert(),groups={};
  root.traverse(function(o){if(!o.isMesh||o.isSkinnedMesh||o.isInstancedMesh||Array.isArray(o.material))return;var g=o.geometry;
    if(g.groups&&g.groups.length>1)return;if(g.morphAttributes&&Object.keys(g.morphAttributes).length)return;
    for(var q=o;q&&q!==root;q=q.parent){if(q.userData&&q.userData.part)return}
    var sig=Object.keys(g.attributes).sort().map(function(n){return n+g.attributes[n].itemSize}).join(','),k=o.material.uuid+'#'+sig;(groups[k]=groups[k]||[]).push(o)});
  var GET=['getX','getY','getZ','getW'];
  Object.keys(groups).forEach(function(id){var list=groups[id];if(list.length<2)return;
    var names=Object.keys(list[0].geometry.attributes),out={},idx=[],base=0,v=new T.Vector3();names.forEach(function(n){out[n]=[]});
    list.forEach(function(o){var g=o.geometry,m=new T.Matrix4().multiplyMatrices(inv,o.matrixWorld),nm=new T.Matrix3().getNormalMatrix(m),flip=m.determinant()<0,cnt=g.attributes.position.count,i,c;
      names.forEach(function(n){var A=g.attributes[n],dst=out[n],sz=A.itemSize;
        for(i=0;i<cnt;i++){if(n==='position'){v.fromBufferAttribute(A,i).applyMatrix4(m);dst.push(v.x,v.y,v.z)}
          else if(n==='normal'){v.fromBufferAttribute(A,i).applyMatrix3(nm).normalize();dst.push(v.x,v.y,v.z)}
          else if(n==='tangent'){v.set(A.getX(i),A.getY(i),A.getZ(i)).transformDirection(m);dst.push(v.x,v.y,v.z,A.getW(i)*(flip?-1:1))}
          else for(c=0;c<sz;c++)dst.push(A[GET[c]](i))}});
      var nn=g.index?g.index.count:cnt,a,b,d;
      for(i=0;i<nn;i+=3){a=g.index?g.index.getX(i):i;b=g.index?g.index.getX(i+1):i+1;d=g.index?g.index.getX(i+2):i+2;if(flip)idx.push(a+base,d+base,b+base);else idx.push(a+base,b+base,d+base)}
      base+=cnt});
    var G=new T.BufferGeometry();names.forEach(function(n){var A=list[0].geometry.attributes[n];G.setAttribute(n,new T.Float32BufferAttribute(out[n],A.itemSize))});
    G.setIndex(base>65535?new T.Uint32BufferAttribute(idx,1):new T.Uint16BufferAttribute(idx,1));
    var M=new T.Mesh(G,list[0].material);M.userData=Object.assign({},list[0].userData);M.castShadow=list[0].castShadow;M.receiveShadow=list[0].receiveShadow;
    list.forEach(function(o){o.parent.remove(o);o.geometry.dispose()});root.add(M)});
  /* 3) peinture et feux : listes refaites avec les matériaux restants */
  paintMats=[];LT={head:[],tail:[],sig:[]};
  root.traverse(function(o){if(!o.isMesh||Array.isArray(o.material))return;var u=o.userData||{},mt=o.material;if(u.paint&&paintMats.indexOf(mt)<0)paintMats.push(mt);if(u.light&&LT[u.light]&&LT[u.light].indexOf(mt)<0)LT[u.light].push(mt)})}
/* la voiture posée sur la plateforme : on cherche les roues (pièces rondes sur les côtés) et on met leur bas au niveau du plateau */
function ground(root){root.updateMatrixWorld(true);var b=new T.Box3(),sz=new T.Vector3(),ce=new T.Vector3(),W=[],cb=new T.Box3().setFromObject(root),cw=cb.max.x-cb.min.x;
  root.traverse(function(o){if(!o.isMesh)return;b.setFromObject(o);b.getSize(sz);b.getCenter(ce);
    if(sz.y>.4&&sz.y<.95&&Math.abs(sz.y-sz.z)<.25*sz.y&&sz.x>.08&&sz.x<.45&&Math.abs(ce.x)>cw*.22)W.push(b.min.y)});
  if(W.length<2){ST.ground='?';return}W.sort(function(a,c){return a-c});var wy=null;for(var i=0;i<W.length-1&&wy===null;i++)if(W[i+1]-W[i]<.02)wy=W[i];if(wy===null){ST.ground='?';return}var off=PLAT_Y-wy;
  ST.ground=off.toFixed(3);if(Math.abs(off)>.004&&Math.abs(off)<.3)root.position.y+=off}
function hasCar(){return carId()}
function opens(){return K.filter(function(k){return !!parts[k]})}

/* ---------- apparence : décor, LED, carrosserie (suivent le profil, le jour et la nuit) ---------- */
var look=(function(){
  var cur=null,led=new T.Color('#1e6ffe'),ledFrom=led.clone(),ledTo=led.clone(),ledT=0,gain=1,
      paint=new T.Color('#c9cdd3'),pFrom=paint.clone(),pTo=paint.clone(),pT=0,bgT=0,texCache={};
  function tex(src,cb){if(texCache[src]){cb(texCache[src]);return}new T.TextureLoader().load(src,function(t){t.colorSpace=T.SRGBColorSpace;texCache[src]=t;
    var ks=Object.keys(texCache);if(ks.length>3){var o=texCache[ks[0]];delete texCache[ks[0]];if(o!==BG0.material.map&&o!==BG1.material.map)o.dispose()}cb(t)})}
  function apply(force){var id=carId();if(id&&id!==CAR)loadCar(id);if(!LOOK)return;var s=LOOK.showroom(LIGHT);
    if(s&&(!cur||cur.id!==s.id||force)){var first=!cur;cur=s;box.style.backgroundImage='url('+s.img+')';
      ledFrom=led.clone();ledTo=new T.Color(s.led);ledT=first?0:performance.now();gain=s.gain;
      tex(s.img,function(t){if(cur!==s)return;if(first||!BG1.material.map){BG1.material.map=t;BG1.material.opacity=1;BG1.material.needsUpdate=true;BG0.material.opacity=0;fit()}
        else{BG0.material.map=BG1.material.map;BG0.material.opacity=1;BG0.material.needsUpdate=true;BG1.material.map=t;BG1.material.opacity=0;BG1.material.needsUpdate=true;bgT=performance.now();fit()}dirty()})}
    try{pFrom=paint.clone();pTo=new T.Color(LOOK.paint());pT=performance.now()}catch(e){}
    if(plat&&ptN&&ptJ)platMats.forEach(function(m){var w=LIGHT?ptJ:ptN;if(m.map!==w){m.map=w;m.needsUpdate=true}});
    dirty()}
  /* cadrage du décor comme « center/cover » */
  function fit(){var a=(CV.clientWidth||1280)/(CV.clientHeight||720);[BG0,BG1].forEach(function(b){var t=b.material.map;if(!t||!t.image)return;var ia=t.image.width/t.image.height;
    t.repeat.set(1,1);t.offset.set(0,0);if(a>ia){t.repeat.y=ia/a;t.offset.y=(1-t.repeat.y)/2}else{t.repeat.x=a/ia;t.offset.x=(1-t.repeat.x)/2}t.needsUpdate=true})}
  function step(now){var busy=false,k;
    if(ledT){k=smooth((now-ledT)/CFG.LOOK_MS);led.copy(ledFrom).lerp(ledTo,k);if(k>=1)ledT=0;else busy=true}else led.copy(ledTo);
    if(pT){k=smooth((now-pT)/CFG.LOOK_MS);paint.copy(pFrom).lerp(pTo,k);if(k>=1)pT=0;else busy=true}
    if(bgT){k=smooth((now-bgT)/CFG.LOOK_MS);BG1.material.opacity=k;if(k>=1){bgT=0;BG0.material.opacity=0}else busy=true}
    var g=gain;b1.color.copy(led);b2.color.copy(led);b1.intensity=b2.intensity=6*g;
    platMats.forEach(function(m){m.emissive.copy(led);m.emissiveIntensity=.85*g});
    paintMats.forEach(function(m){m.color.copy(paint)});
    return busy}
  return {apply:apply,step:step,fit:fit,paintNow:function(){try{paint.set(LOOK.paint());pTo.copy(paint);pT=0}catch(e){}}}})();

/* ---------- état de la voiture réelle : portes, phares, warnings ---------- */
function rawMask(){var all=0;K.forEach(function(k,b){if(parts[k])all|=1<<b});if(window.__m3dExp)return all;
  var o={};try{o=window.__dOpen?window.__dOpen():{}}catch(e){}var m=0;K.forEach(function(k,b){if(o[k])m|=1<<b});return m&all}
function warnOn(){return lsGet('m3dWarn','0')==='1'||!!(window.__carv&&window.__carv.warn===true)}
function lightsOn(){var e=document.getElementById('lights');return !!(e&&e.classList.contains('on'))}
var _ar={t:0,v:true};function autoRot(){var n=Date.now();if(n-_ar.t>1000){_ar.t=n;_ar.v=lsGet('m3dAuto','1')!=='0'}return _ar.v}
function pageVisible(){var st=document.getElementById('stage'),pg=st&&st.getAttribute('data-page');return pg==='home'||pg==='veh'||document.body.classList.contains('persOn')}
var litNow=null,blinkOn=false;
function lights(){var hl=lightsOn(),bl=warnOn()&&blinkOn;litNow=hl;
  LT.head.forEach(function(m){m.emissive.setHex(hl?0xffffff:0);m.emissiveIntensity=hl?3:0});
  LT.tail.forEach(function(m){m.emissive.setHex(0xff1010);m.emissiveIntensity=hl?2.6:0.9});
  LT.sig.forEach(function(m){m.emissive.setHex(bl?0xff8a00:0);m.emissiveIntensity=bl?4:0});dirty()}

/* ---------- portes ---------- */
var doors={mask:0,
  set:function(m){if(m===this.mask)return;this.mask=m;var now=performance.now();K.forEach(function(k,b){var p=parts[k];if(!p)return;var to=(m>>b)&1;if(p.to===to)return;p.from=p.v;p.to=to;p.t0=now});dirty()},
  reset:function(){this.mask=0;K.forEach(function(k){var p=parts[k];if(p){p.v=p.from=p.to=0;p.t0=0;place(p)}})},
  step:function(now){var busy=false;K.forEach(function(k){var p=parts[k];if(!p||!p.t0)return;var k2=clamp((now-p.t0)/CFG.DOOR_MS,0,1);p.v=p.from+(p.to-p.from)*smooth(k2);place(p);if(k2>=1)p.t0=0;else busy=true});return busy}};
function place(p){var v=ease(p.v)*p.max;if(p.axis==='y')p.p.rotation.y=v;else p.p.rotation.x=v}

/* ---------- caméra : rotation (R), vue du dessus (T), habitacle (I) ---------- */
var CAMS={R:{pos:new T.Vector3(0,1.5017708643862162,9.481808069713322),look:new T.Vector3(0,0,0),up:new T.Vector3(0,1,0),fov:28},
          T:{pos:new T.Vector3(0,17,0.001),look:new T.Vector3(0,0,0),up:new T.Vector3(0,0,-1),fov:28},
          S:{pos:new T.Vector3(0,2.3,9.2),look:new T.Vector3(0,.45,0),up:new T.Vector3(0,1,0),fov:28},
          H:{pos:new T.Vector3(0,3.6,8.8),look:new T.Vector3(0,.5,0),up:new T.Vector3(0,1,0),fov:28},
          B:{pos:new T.Vector3(0,5.6,10.6),look:new T.Vector3(0,.15,-.6),up:new T.Vector3(0,1,0),fov:30},
          I:{pos:new T.Vector3(.36,1.2,-.4),look:new T.Vector3(.36,1.02,4),up:new T.Vector3(0,1,0),fov:58}};
var camState={from:'R',to:'R',t0:0,k:1,cb:null};
function camGo(to,cb){if(camState.to===to&&!camState.t0){cb&&cb();return}
  /* depuis une position intermédiaire : on repart d'où on est */
  camState.fromPos=cam.position.clone();camState.fromLook=camLook.clone();camState.fromUp=cam.up.clone();camState.fromFov=cam.fov;
  camState.to=to;camState.t0=performance.now();camState.cb=cb||null;dirty()}
var camLook=new T.Vector3();
function camApply(c){cam.position.copy(c.pos);cam.up.copy(c.up);camLook.copy(c.look);cam.fov=c.fov;cam.lookAt(camLook)}
function camStep(now){if(!camState.t0)return false;var k=smooth((now-camState.t0)/CFG.CAM_MS),D=CAMS[camState.to];
  /* montée en arc : la caméra passe au-dessus plutôt qu'en ligne droite */
  var p=camState.fromPos.clone().lerp(D.pos,k);if(camState.to!=='I'&&camState.from!=='I'&&camState.to!==camState.from)p.y+=Math.sin(k*Math.PI)*0.6;
  cam.position.copy(p);camLook.copy(camState.fromLook).lerp(D.look,k);cam.up.copy(camState.fromUp).lerp(D.up,k).normalize();cam.fov=camState.fromFov+(D.fov-camState.fromFov)*k;cam.lookAt(camLook);
  if(k>=1){camState.t0=0;camState.from=camState.to;var cb=camState.cb;camState.cb=null;cb&&cb()}return true}

/* ---------- plateau : rotation lente, régulière ---------- */
var ang=POSE,vel=0,velT=0,settleTo=null,settleCb=null;
function turnStep(dt){
  /* 3.6.1 : mise en pose à durée fixe et courbe douce (même rythme que la caméra : plus d'à-coup) */
  if(settleTo!==null){var k=smooth((performance.now()-stT0)/stDur);ang=wrap(stFrom+stD*k);vel=0;
    if(k>=1){ang=settleTo;settleTo=null;var cb=settleCb;settleCb=null;cb&&cb()}return true}
  var want=rotating()?2*Math.PI/CFG.TURN_S*CFG.ROT_SPEED:0;
  if(vel!==want){var acc=2*Math.PI/CFG.TURN_S/(CFG.EASE_MS/1000);vel=want>vel?Math.min(want,vel+acc*dt):Math.max(want,vel-acc*3*dt)}
  if(vel){ang=wrap(ang-vel*dt);return true}return false}
var stFrom=0,stD=0,stT0=0,stDur=1;
function settle(a,cb){settleTo=wrap(a);settleCb=cb;stFrom=ang;stD=short(ang,settleTo);stT0=performance.now();stDur=Math.max(450,Math.min(CFG.CAM_MS,Math.abs(stD)*700));dirty()}

/* ---------- logique d'état ---------- */
var running=false,mode='rot',drag=null,hold=0,closeT=0,dive=0,posed=false;
function rotating(){return running&&mode==='rot'&&!dive&&!drag&&Date.now()>=hold&&autoRot()&&pageVisible()&&!document.body.classList.contains('inBg')&&!document.hidden&&!!carObj}
/* 3.6 : où regarder quand quelque chose s'ouvre.
   Une seule pièce : la caméra se place de son côté (porte vue de profil, capot de 3/4 avant, coffre de 3/4 arrière).
   Plusieurs pièces : vue arrière, un peu en hauteur et inclinée vers nous, pour voir d'un coup tout ce qui est ouvert. */
var SIDE=0.45,VUES={
  d1:{ang:-Math.PI/2+SIDE,cam:'S'},d3a:{ang:-Math.PI/2-SIDE,cam:'S'},
  d2:{ang:Math.PI/2-SIDE,cam:'S'},d3b:{ang:Math.PI/2+SIDE,cam:'S'},
  d5:{ang:0.6,cam:'H'},d4:{ang:Math.PI-0.6,cam:'H'},
  multi:{ang:Math.PI,cam:'B'},warn:{ang:POSE,cam:'R'}};
function vueFor(m){var open=K.filter(function(k,b){return (m>>b)&1});if(!open.length)return VUES.warn;return open.length>1?VUES.multi:VUES[open[0]]}
var vueCur=null;
function vueGo(v){if(vueCur===v)return;vueCur=v;document.body.classList.toggle('m3dTop',v===VUES.multi);settle(v.ang);camGo(v.cam)}
function tick(){
  if(!running||!carObj)return;
  var bg=document.body.classList.contains('inBg');if(bg||document.hidden)return;
  var wb=warnOn()&&Math.floor(Date.now()/450)%2===0;if(wb!==blinkOn){blinkOn=wb;lights()}
  if(lightsOn()!==litNow)lights();
  if(dive)return;
  var m=rawMask(),w=warnOn();
  if(mode==='rot'){
    if(m||w){mode='door';closeT=0;vueCur=null;doors.set(m);vueGo(vueFor(m));return}
    if(!autoRot()&&!posed&&!drag){posed=true;settle(POSE)}
    if(autoRot())posed=false;
    if(rotating())dirty();
  }else{
    if(m!==doors.mask){closeT=0;doors.set(m);if(m)vueGo(vueFor(m));return}
    if(!m&&!w){if(!closeT){closeT=Date.now()+CFG.CLOSE_MS}
      else if(Date.now()>closeT){closeT=0;vueCur=null;document.body.classList.remove('m3dTop');
        /* 3.6.1 : retour symétrique de l'ouverture : la caméra redescend ET la voiture revient en pose en même temps,
           puis la rotation reprend en douceur depuis cette pose (plus de second mouvement après coup) */
        settle(POSE);posed=true;vel=0;camGo('R',function(){mode='rot';hold=Date.now()+600;dirty()})}}
    else closeT=0}
}
setInterval(tick,80);

/* ---------- rendu à la demande ---------- */
var raf=0,need=true,lastT=0,lastR=0,frames=0,t0=performance.now(),slow=0,W=0,H=0;
function dirty(){need=true;kick()}
function kick(){if(!raf&&running)raf=requestAnimationFrame(loop)}
function resize(){var r=box.getBoundingClientRect();if(!r.width)return;var dpr=Math.min(window.devicePixelRatio||1,2),q=QL[qi].pr;
  var w=Math.round(Math.min(1600,r.width*dpr*q)),h=Math.round(w*r.height/r.width);if(w!==W||h!==H){W=w;H=h;R.setSize(w,h,false);look.fit()}
  /* même cadrage que les images d'avant : le cadre 1000×500 de la caméra tombe sur #m3v */
  var v=V.getBoundingClientRect(),s=(v.width||r.width*1000/1280)/1000,ox=(r.left-(v.width?v.left:r.left+140*s))/s+140,oy=(r.top-(v.width?v.top:r.top+120*s))/s+20;
  cam.aspect=1280/720;cam.setViewOffset(1280,720,ox,oy,r.width/s,r.height/s);cam.updateProjectionMatrix();need=true}
window.addEventListener('resize',function(){if(running){resize();dirty()}});
function loop(now){raf=0;if(!running||document.hidden)return;
  var dt=Math.min(.1,(now-(lastT||now))/1000);
  if(now-lastR<1000/CFG.FPS_CAP-3){if(busy())raf=requestAnimationFrame(loop);return}
  lastT=now;
  var a=turnStep(dt),b=doors.step(now),c=camStep(now),d=look.step(now);
  if(!camState.t0&&camState.to==='R'&&!a&&!c)camApply(CAMS.R);
  turn.rotation.y=ang;
  if(a||b||c||d||need){need=false;lastR=now;bgVis();knob.pos();R.clear();R.render(BS,BC);R.clear(false,true,false);R.render(S,cam);frames++}
  var el=now-t0;if(el>2000){ST.fps=Math.round(frames*1000/el);
    if(rotating()&&pageVisible()){if(ST.fps<CFG.MIN_FPS)slow++;else slow=0;if(slow>=2&&qi<QL.length-1){qi++;ST.q=qi;slow=0;lsPut(QKEY,String(qi));applyQ()}}
    frames=0;t0=now}
  anim=a||b||c||d;if(anim||busy())raf=requestAnimationFrame(loop)}
var anim=false;
function busy(){return anim||rotating()||settleTo!==null||!!camState.t0||!!drag||need}
function applyQ(){var q=QL[qi];R.shadowMap.enabled=q.sh>0;DL.castShadow=q.sh>0;lightsQ();S.traverse(function(o){if(o.material)(Array.isArray(o.material)?o.material:[o.material]).forEach(function(m){m.needsUpdate=true})});W=0;resize();dirty()}
camApply(CAMS.R);

/* ---------- curseur de rotation sous la voiture ---------- */
var knob=(function(){var SL=document.createElement('div');SL.id='m3sl';SL.innerHTML='<i class="tr"></i><b class="kn"><svg viewBox="0 0 24 24"><path d="M9 7l-5 5 5 5M15 7l5 5-5 5"/></svg></b>';(document.getElementById('stage')||box).appendChild(SL);
  var KN=SL.querySelector('.kn'),sd=null,on=false;function place(f,big){KN.style.transform='translateX('+(f*340).toFixed(1)+'px)'+(big?' scale(1.15)':'')}
  function show(){return running&&mode==='rot'&&!dive&&pageVisible()&&!document.body.classList.contains('inBg')}
  var lastF=-1;function lp(){on=false;SL.style.visibility=show()?'':'hidden'}
  function at(e){var r=SL.getBoundingClientRect();return Math.max(0,Math.min(.999,(e.clientX-r.left)/r.width))}
  SL.addEventListener('pointerdown',function(e){if(!show())return;e.preventDefault();e.stopPropagation();SL.setPointerCapture(e.pointerId);posed=true;window.__m3dDrag=true;window.__m3dDragAt=Date.now();sd=1;drag=drag||{k:1};SL.classList.add('on');var f=at(e);place(f,1);ang=wrap((1-f)*2*Math.PI);dirty()});
  SL.addEventListener('pointermove',function(e){if(!sd)return;e.stopPropagation();var f=at(e);place(f,1);ang=wrap((1-f)*2*Math.PI);dirty()});
  function end(){if(!sd)return;sd=null;drag=null;SL.classList.remove('on');hold=Date.now()+CFG.RESUME_MS;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450);knob.ensure();dirty()}
  SL.addEventListener('pointerup',end);SL.addEventListener('pointercancel',end);
  return {pos:function(){if(sd||!show())return;var f=Math.round((1-wrap(ang)/(2*Math.PI))*340);if(f!==lastF){lastF=f;place(f/340)}},
    ensure:function(){if(!show()&&!sd)SL.style.visibility='hidden';else if(show()&&SL.style.visibility==='hidden'){SL.style.visibility='';lastF=-1;knob.pos()}}}})();
setInterval(function(){knob.ensure()},500);

/* ---------- rotation au doigt sur l'accueil ---------- */
function inCar(e){var st=document.getElementById('stage');if(!st)return false;var r=st.getBoundingClientRect(),sc=r.width/1280,x=(e.clientX-r.left)/sc,y=(e.clientY-r.top)/sc;return x>230&&x<1050&&y>150&&y<560}
document.addEventListener('pointerdown',function(e){if(!running||mode!=='rot'||dive||!carObj)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit')||document.body.classList.contains('persOn'))return;
  if(e.target.closest&&e.target.closest('.gc,.tile,button,#tiles,#top,#m3btn,#m3ctl,.cw,#m3sl'))return;if(!inCar(e))return;
  posed=true;settleTo=null;vel=0;drag={x:e.clientX,a:ang};window.__m3dDrag=true;window.__m3dDragAt=Date.now();dirty()},true);
document.addEventListener('pointermove',function(e){if(!drag||drag.k)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280;
  ang=wrap(drag.a-(e.clientX-drag.x)/sc*CFG.DRAG_K);dirty()},true);
function up(){if(drag&&!drag.k){drag=null;hold=Date.now()+CFG.RESUME_MS;window.__m3dDragAt=Date.now();setTimeout(function(){window.__m3dDrag=false},450);dirty()}}
document.addEventListener('pointerup',up,true);document.addEventListener('pointercancel',up,true);

/* ---------- habitacle (onglet Véhicule) : la caméra entre par la portière conducteur ---------- */
function canDive(){return !!carObj&&FICHE.dive===true}
function diveTo(on,cb){if(!canDive()){cb&&cb();return}if(on&&dive===1||!on&&dive===0){cb&&cb();return}
  if(on){window.__m3dExp=false;mode='rot';doors.set(0);document.body.classList.remove('m3dTop')}
  dive=2;if(on){cab.visible=true;cab.intensity=1.2}
  var go=function(){camGo(on?'I':'R',function(){dive=on?1:0;if(!on){cab.intensity=0;cab.visible=false;posed=false}document.body.classList.toggle('m3dIn',on);dirty();cb&&cb()})};
  if(on)settle(0,go);else go()}
new MutationObserver(function(){var pg=document.getElementById('stage').getAttribute('data-page');if(!running)return;if(pg==='veh')diveTo(true);else if(dive===1&&!window.__m3dInside)diveTo(false);knob.ensure();dirty()}).observe(document.getElementById('stage'),{attributes:true,attributeFilter:['data-page']});

/* ---------- interface publique ---------- */
var platReady=false;
window.M3DV={
  start:function(){var id=carId();if(running||!id)return;if(window.__licLocked&&__licLocked())return;running=true;resize();
    var go=function(){platReady=true;look.apply(true);if(carObj&&CAR===id){cvOp(1)}else loadCar(id);dirty()};
    if(platReady)go();else loadPlat(go);kick();knob.ensure()},
  free:function(){if(!running&&carObj){turn.remove(carObj);dispose(carObj);carObj=null;CAR=null;parts={};LT={head:[],tail:[],sig:[]};paintMats=[]}},   /* mémoire faible (onTrimMemory) */
  stop:function(){running=false;cvOp(0);if(dive){dive=0;camState.t0=0;camState.to=camState.from='R';camApply(CAMS.R);cab.intensity=0;cab.visible=false;document.body.classList.remove('m3dIn')}},
  setColor:function(){look.apply()},
  look:function(){look.apply()},
  car:function(id){if(!id)return CAR;if(id!==CAR&&running)loadCar(id);return id},
  cars:function(){return LOOK&&LOOK.CARLIST?LOOK.CARLIST.map(function(x){return x.id}):[]},hasCar:hasCar,
  theme:function(light){light=!!light;if(light===LIGHT)return;LIGHT=light;look.apply()},
  explode:function(){window.__m3dExp=!window.__m3dExp;return window.__m3dExp},
  inside:function(){var on=dive!==1;window.__m3dInside=on;diveTo(on);return on},
  opens:function(){return opens()},
  lite:function(){return ''},
  cfg:CFG};
document.addEventListener('visibilitychange',function(){if(!document.hidden&&running){resize();dirty()}});
/* carte graphique perdue puis rendue par Android : on recharge la page (décor et voiture repartent proprement) */
CV.addEventListener('webglcontextlost',function(e){e.preventDefault();ST.ctxLost=Date.now()},false);
CV.addEventListener('webglcontextrestored',function(){try{if(!sessionStorage.getItem('m3dCtx')){sessionStorage.setItem('m3dCtx','1');setTimeout(function(){sessionStorage.removeItem('m3dCtx')},20000);location.reload()}}catch(e){}},false);
window.__m3dReup=function(){if(window.__m3dFreed){window.__m3dFreed=0;try{if(lsGet('m3d','0')==='1'&&document.getElementById('stage').getAttribute('data-page')==='home'&&!(window.__licLocked&&__licLocked()))M3DV.start()}catch(e){}}dirty()};
window.__m3dDbg=function(){var ri=R.info||{},rr=ri.render||{},mm=ri.memory||{};return {calls:rr.calls,rtris:rr.triangles,geo:mm.geometries,tex:mm.textures,prog:ri.programs?ri.programs.length:0,meshes:ST.meshes,mergeMs:ST.mergeMs,ground:ST.ground,platY:PLAT_Y,gpu:GPU,weak:WEAK,mode:mode,mask:doors.mask,dive:dive,running:running,ang:+ang.toFixed(3),cam:camState.to,car:CAR,opens:opens(),fps:ST.fps,q:qi,tris:ST.tris,size:[W,H],err:ST.err,paint:paintMats.length,lt:[LT.head.length,LT.tail.length,LT.sig.length],col:paintMats[0]?'#'+paintMats[0].color.getHexString():''}};
window.__m3dOk=1;
})();
