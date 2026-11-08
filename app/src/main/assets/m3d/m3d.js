/* Cockpit : voiture 3D sur l'accueil (modèle : GT Cars: Hyperspeed, Sketchfab, CC-BY 4.0) */
(function(){
var C=window.C3D,T=C.THREE,cv=document.getElementById('m3dC');if(!C||!cv)return;
var P={camY:1.15,camZ:9.2,lookY:0.95,len:4.2};
var GL=null,LT=null,beam=null,glow=null,R,S,cam,turn,car=null,parts=[],ang=0.5,tgt=null,drag=null,running=false,lastR=0,lastT=0,holdUntil=0,fz=1,ready=false;
function col(){try{return localStorage.getItem('m3dCol')||'#c9cdd3'}catch(e){return '#c9cdd3'}}
function init(){
  R=new T.WebGLRenderer({canvas:cv,alpha:true,antialias:true,powerPreference:'high-performance'});
  R.setPixelRatio(1);R.setSize(1280,720,false);R.outputColorSpace=T.SRGBColorSpace;R.toneMapping=T.ACESFilmicToneMapping;R.toneMappingExposure=1.05;R.shadowMap.enabled=true;
  S=new T.Scene();var pm=new T.PMREMGenerator(R);S.environment=pm.fromScene(new C.RoomEnvironment(),.04).texture;
  cam=new T.PerspectiveCamera(28,1280/720,.1,100);cam.position.set(0,P.camY,P.camZ);cam.lookAt(0,P.lookY,0);
  var d=new T.DirectionalLight(0xffffff,1.6);d.position.set(3,6,4);d.castShadow=true;d.shadow.mapSize.set(1024,1024);var sc=d.shadow.camera;sc.left=-4;sc.right=4;sc.top=4;sc.bottom=-4;S.add(d);
  var b1=new T.PointLight(0x3a8dff,7,10);b1.position.set(-3,.4,1);S.add(b1);var b2=new T.PointLight(0x3a8dff,7,10);b2.position.set(3,.4,-1);S.add(b2);
  var sh=new T.Mesh(new T.PlaneGeometry(9,9),new T.ShadowMaterial({opacity:.45}));sh.rotation.x=-Math.PI/2;sh.receiveShadow=true;S.add(sh);
  turn=new T.Group();S.add(turn);
  var raw=atob(window.M3D.golf6),u=new Uint8Array(raw.length);for(var i=0;i<raw.length;i++)u[i]=raw.charCodeAt(i);window.M3D.golf6=null;
  var L=new C.GLTFLoader();L.setMeshoptDecoder(C.MeshoptDecoder);
  L.parse(u.buffer,'',function(g){setup(g.scene)},function(e){window.__m3dErr=String(e)});
}
function body(m){var b=new T.Box3();m.updateMatrixWorld(true);m.traverse(function(o){if(o.isMesh&&o.material&&/paint|mechanical_rally|cherrier/i.test(o.material.name))b.expandByObject(o)});return b}
function setup(m){
  var bx=body(m),sz=bx.getSize(new T.Vector3()),k=P.len/Math.max(sz.x,sz.z);m.scale.setScalar(k);
  bx=body(m);var c=bx.getCenter(new T.Vector3());m.position.x-=c.x;m.position.z-=c.z;m.position.y-=bx.min.y;
  turn.add(m);m.updateMatrixWorld(true);bx=body(m);
  var ex=bx.clone().expandByScalar(.15);
  m.traverse(function(o){if(o.isMesh){var ob=new T.Box3().setFromObject(o);if(!ex.containsBox(ob))o.visible=false;o.castShadow=true}});
  paint(col());
  car=m;
  var bf=byName(/^vgolf6_bumper_a_F$/);if(bf.length){var cb=new T.Box3().setFromObject(bf[0]).getCenter(new T.Vector3());fz=cb.z>=0?1:-1}
  /* pièces mobiles : portes, capot, hayon */
  [['d1',/^vgolf6_door(glass)?_FL$/],['d2',/^vgolf6_door(glass)?_FR$/],['d3a',/^vgolf6_door(glass)?_RL$/],['d3b',/^vgolf6_door(glass)?_RR$/],['d5',/^vgolf6_hood_a$/],['d4',/^vgolf6_tailgate_[ab]$/]].forEach(function(q){
    var nodes=byName(q[1]);if(!nodes.length)return;
    var b=new T.Box3();nodes.forEach(function(n){n.traverse(function(o){if(o.isMesh&&/paint/i.test(o.material.name))b.expandByObject(o)})});if(b.isEmpty())nodes.forEach(function(n){b.expandByObject(n)});var cc=b.getCenter(new T.Vector3());
    var piv=new T.Group(),axis,max,h;
    if(q[0]==='d5'){h=new T.Vector3(cc.x,b.max.y,fz>0?b.min.z:b.max.z);axis='x';max=-fz*0.95}
    else if(q[0]==='d4'){h=new T.Vector3(cc.x,b.max.y,fz>0?b.max.z:b.min.z);axis='x';max=fz*1.12}
    else{var sx=cc.x>=0?1:-1;h=new T.Vector3(sx>0?b.max.x:b.min.x,cc.y,fz>0?b.max.z:b.min.z);axis='y';max=-sx*fz*1.15;q.sx=sx}
    piv.position.copy(h);turn.add(piv);nodes.forEach(function(n){piv.attach(n)});
    parts.push({k:q[0],p:piv,axis:axis,max:max,cur:0,sx:q.sx||0});
  });
  LT={head:[],tail:[],sigL:[],sigR:[]};var seen={};
  m.traverse(function(o){if(!o.isMesh||!o.material)return;var mt=o.material,n=mt.name;if(seen[mt.uuid])return;
    var g=/lowbeam|drl_signal|viilights|silver_satin_headlights/i.test(n)&&!/signal_[LR]$/.test(n)?'head':/taillight|chmsl/i.test(n)?'tail':/signal_L$/i.test(n)?'sigL':/signal_R$/i.test(n)?'sigR':null;
    if(!g)return;seen[mt.uuid]=1;mt.emissive=new T.Color(0,0,0);LT[g].push(mt);if(/drl_signal_L/.test(n))LT.sigL.push(mt);if(/drl_signal_R/.test(n))LT.sigR.push(mt)});
  var hb=byName(/^vgolf6_bumper_a_F$/),fb=hb.length?new T.Box3().setFromObject(hb[0]):bx;
  beam=new T.SpotLight(0xdfe9ff,0,9,.5,.6,1.2);beam.position.set(0,.7,fz*(bx.max.z-.2));beam.target.position.set(0,0,fz*(bx.max.z+4));turn.add(beam);turn.add(beam.target);
  glow=new T.PointLight(0xff2a1a,0,3,2);glow.position.set(0,.8,-fz*(bx.max.z+.3));turn.add(glow);
  var tex=(function(){var c=document.createElement('canvas');c.width=c.height=64;var x=c.getContext('2d'),g=x.createRadialGradient(32,32,0,32,32,32);g.addColorStop(0,'rgba(255,255,255,1)');g.addColorStop(.25,'rgba(255,255,255,.55)');g.addColorStop(1,'rgba(255,255,255,0)');x.fillStyle=g;x.fillRect(0,0,64,64);return new T.CanvasTexture(c)})();
  GL={head:[],tail:[],sig:[]};
  function spots(re,grp,color,size){var pts=[];m.traverse(function(o){if(o.isMesh&&o.material&&re.test(o.material.name)){var b=new T.Box3().setFromObject(o);if(b.isEmpty()||b.getSize(new T.Vector3()).x>1.2)return;var c=b.getCenter(new T.Vector3());pts.push(c)}});
    var cx=bx.getCenter(new T.Vector3()).x,G4=[[],[],[],[]];pts.forEach(function(p){G4[(p.x>=cx?0:1)+(p.z>=0?0:2)].push(p)});
    G4.forEach(function(a){if(!a.length)return;var c=new T.Vector3();a.forEach(function(p){c.add(p)});c.multiplyScalar(1/a.length);
      var sp=new T.Sprite(new T.SpriteMaterial({map:tex,color:color,blending:T.AdditiveBlending,transparent:true,depthWrite:false,opacity:0}));sp.scale.set(size,size,1);sp.position.copy(c);sp.position.z+=(c.z>0?.06:-.06);turn.add(sp);GL[grp].push(sp)})}
  spots(/lowbeam|highbeam/i,'head',0xeaf2ff,.75);spots(/taillight/i,'tail',0xff2010,.45);spots(/signal_[LR]$/i,'sig',0xff8a00,.38);
  ready=true;window.__m3dOk=1;window.__m3dInfo=JSON.stringify({fz:fz,parts:parts.map(function(p){return [p.k,p.axis,p.max.toFixed(2),p.sx,p.p.position.x.toFixed(2),p.p.position.z.toFixed(2)]})});
}
function byName(re){var a=[];car&&car.traverse(function(o){if(o.name&&re.test(o.name)){for(var i=0;i<a.length;i++){var p=o.parent;while(p){if(p===a[i])return;p=p.parent}}a.push(o)}});return a}
function paint(hex){if(!S)return;S.traverse(function(o){if(o.isMesh&&o.material){var mt=o.material;
  if(mt.userData.pnt===undefined){var cc0=mt.color;mt.userData.pnt=/paint/i.test(mt.name)||(cc0&&cc0.b>.6&&cc0.r<.08&&cc0.g<.2)}if(mt.userData.pnt){mt.color=new T.Color(hex);mt.metalness=.45;mt.roughness=.38;mt.envMapIntensity=.9}
  if(/glass/i.test(mt.name)){mt.color=new T.Color('#141c28');mt.transparent=true;mt.opacity=.62;mt.metalness=0;mt.roughness=.05;mt.envMapIntensity=1.2}}})}
function view(k,sx){var d;if(k==='d4')d=[.45,-fz];else if(k==='d5')d=[.45,fz];else d=[sx,fz*.55];return Math.atan2(-d[0],d[1])}
function lightsOn(){var e=document.getElementById('lights');if(window.__m3dTestL!==undefined)return !!window.__m3dTestL;return !!(e&&e.classList.contains('on'))}
function warnOn(){if(window.__m3dTestW!==undefined)return !!window.__m3dTestW;try{if(localStorage.getItem('m3dWarn')==='1')return true}catch(e){}var v=window.__carv&&window.__carv.warn;return v===true}
function state(){var o={};try{o=window.__dOpen?window.__dOpen():{}}catch(e){}var t=window.__m3dTest||{};for(var k in t)o[k]=t[k];return o}
var prevOpen={};
function step(t){
  requestAnimationFrame(step);
  var dt=Math.min(.1,(t-lastT)/1000||0);lastT=t;
  if(!running||!ready)return;
  var st=document.getElementById('stage'),vis=!document.hidden&&(!st||st.getAttribute('data-page')==='home');
  if(!vis)return;
  if(t-lastR<33)return;lastR=t;
  var o=state(),any=false,newest=null;
  parts.forEach(function(p){var on=!!o[p.k];if(on)any=true;if(on&&!prevOpen[p.k])newest=p;prevOpen[p.k]=on;
    var g=on?1:0;p.cur+=(g-p.cur)*Math.min(1,dt*3.2);if(Math.abs(g-p.cur)<.002)p.cur=g;
    var a=p.max*(1-Math.pow(1-p.cur,3));if(p.axis==='y')p.p.rotation.y=a;else p.p.rotation.x=a});
  if(newest)tgt=view(newest.k,newest.sx);
  if(LT){var hl=lightsOn(),wn=warnOn(),bl=wn&&(Math.floor(t/400)%2===0);
    var SG=LT.sigL.concat(LT.sigR);
    LT.tail.forEach(function(m){m.emissive.setRGB(hl?1:0,0,0);m.emissiveIntensity=hl?1.6:0});
    LT.head.forEach(function(m){if(bl&&SG.indexOf(m)>=0)return;m.emissive.setRGB(hl?1:0,hl?1:0,hl?.95:0);m.emissiveIntensity=hl?2.2:0});
    SG.forEach(function(m){if(bl){m.emissive.setRGB(1,.45,0);m.emissiveIntensity=3}else if(LT.head.indexOf(m)<0){m.emissive.setRGB(0,0,0);m.emissiveIntensity=0}});
    if(GL){GL.head.forEach(function(s){s.material.opacity=hl?.95:0});GL.tail.forEach(function(s){s.material.opacity=hl?.8:0});GL.sig.forEach(function(s){s.material.opacity=bl?1:0})}
    beam.intensity=hl?18:0;glow.intensity=hl?2.5:0}
  if(!any)tgt=null;
  if(drag){}else if(tgt!==null){var df=((tgt-ang)%(Math.PI*2)+Math.PI*3)%(Math.PI*2)-Math.PI;ang+=df*Math.min(1,dt*2.5)}
  else if(t>holdUntil)ang+=dt*.22;
  turn.rotation.y=ang;
  R.render(S,cam);
}
document.addEventListener('pointerdown',function(e){if(!running)return;var st=document.getElementById('stage');if(!st||st.getAttribute('data-page')!=='home'||document.body.classList.contains('edit'))return;
  if(e.target.id!=='p-home'&&e.target.id!=='m3dC')return;if(e.clientY<0)return;drag={x:e.clientX,a:ang}},true);
document.addEventListener('pointermove',function(e){if(!drag)return;var sc=(document.getElementById('stage').getBoundingClientRect().width||1280)/1280;ang=drag.a+(e.clientX-drag.x)/sc*.008},true);
function up(){if(drag){drag=null;holdUntil=performance.now()+4000;tgt=null}}
document.addEventListener('pointerup',up,true);document.addEventListener('pointercancel',up,true);
window.M3DV={start:function(){if(!R)init();running=true},stop:function(){running=false},setColor:function(h){paint(h)}};
requestAnimationFrame(step);
})();
