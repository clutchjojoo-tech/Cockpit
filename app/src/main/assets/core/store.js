/* Cockpit — core/store.js (phase 2 — version de test privée 2.7, non publiée)
   Registre des réglages, version du stockage (cfgV), migration protégée.
   Règles : on copie avant de transformer, on n'efface jamais une ancienne clé,
   chaque étape est notée dans un journal (cfgMig) et peut être rejouée sans effet de bord. */
(function(root){
'use strict';
var C=root.Cockpit=root.Cockpit||{};
var LS=root.localStorage, A=root.Android||null;
var TARGET=1;                       // version de stockage visée par cette mise à jour
var PHOTOS=['fond_jour.jpg','fond_on.jpg','fond_off.jpg','fond_lock.jpg','fond_prof.jpg'];

function get(k,d){try{var v=LS.getItem(k);return v===null?d:v}catch(e){return d}}
function put(k,v){try{LS.setItem(k,String(v));return true}catch(e){return false}}
function call(f,d){try{var r=f();return r===undefined?d:r}catch(e){return d}}
function allLocal(){var o={};for(var i=0;i<LS.length;i++){var k=LS.key(i);o[k]=LS.getItem(k)}return o}
function log(msg){try{var l=JSON.parse(get('cfgLog','[]'));l.push(new Date().toISOString().slice(0,19)+' '+msg);while(l.length>60)l.shift();put('cfgLog',JSON.stringify(l))}catch(e){}}

/* ---------- Sauvegarde complète (page + Android + photos) ---------- */
/* Côté Android (à ajouter en phase 2, voir PATCH-Java) :
   backupFull(localJson) -> id   : écrit files/sauvegardes/<id>/ {local.json, prefs.json, photos, manifest.json}
   restoreFull(id)       -> json : remet prefs.json et les photos, renvoie local.json
   listBackups()         -> json : [{id, date, taille, ok}] (3 dernières gardées) */
function backupFull(reason){
  var snap={app:'cockpit',kind:'full',v:2,reason:reason,date:new Date().toISOString(),cfgV:get('cfgV','0'),ls:allLocal()};
  var js=JSON.stringify(snap);
  if(A&&A.backupFull){var id=call(function(){return A.backupFull(js)},'');return id?{ok:true,id:id,where:'android',size:js.length}:{ok:false}}
  // sans Android (navigateur, démo) : copie en mémoire seulement
  C._memBackup=js;return {ok:true,id:'mem',where:'memory',size:js.length};
}
function restoreFull(id){
  var js=null;
  if(id==='mem')js=C._memBackup;else if(A&&A.restoreFull)js=call(function(){return A.restoreFull(id)},null);
  if(!js)return false;
  var o;try{o=JSON.parse(js)}catch(e){return false}
  if(!o||!o.ls)return false;
  // on remet exactement l'état sauvegardé : clés ajoutées depuis retirées, valeurs d'origine réécrites
  var now=allLocal();Object.keys(now).forEach(function(k){if(!(k in o.ls)&&k!=='cfgLog'&&k!=='cfgFail')LS.removeItem(k)});
  Object.keys(o.ls).forEach(function(k){put(k,o.ls[k])});
  return true;
}

/* ---------- Étapes de migration v0 -> v1 (idempotentes) ---------- */
function androidVoice(){var s=call(function(){return A.getVoice()},'');if(!s)return null;var p=s.split(',');return{on:p[0]==='1'?'1':'0',vol:String(parseInt(p[1],10)||90)}}
var STEPS=[
 {id:'sources',run:function(ctx){
   /* Réglages à deux sources : on garde la valeur RÉELLEMENT utilisée à l'écran ou à la voix. */
   if(!A)return 'sans Android : rien à fusionner';
   var out=[];
   // Prénom : Cockpit lit Android au démarrage et pour le salut ; la page sert de repli si Android est vide.
   var pa=call(function(){return A.getPrenom()},''),pp=get('prenom','');
   if(pa&&pa!==pp){ctx.keep('prenom');put('prenom',pa);out.push('prenom ← Android')}
   // Voix : sur le poste, seule la valeur Android est lue (vOn/vVol de la page ne sont jamais relus).
   var v=androidVoice();
   if(v){if(get('vOn',null)!==v.on){ctx.keep('vOn');put('vOn',v.on);out.push('vOn ← Android')}
         if(get('vVol',null)!==v.vol){ctx.keep('vVol');put('vVol',v.vol);out.push('vVol ← Android')}}
   // Luminosité : Android reste la référence ; la page reçoit une copie.
   var b=call(function(){return String(A.getBrightness())},null);
   if(b&&get('bright',null)!==b){ctx.keep('bright');put('bright',b);out.push('bright ← Android')}
   // Langue et alerte de vitesse : la page fait foi (déjà recopiées vers Android à chaque changement).
   return out.length?out.join(', '):'déjà cohérent';
 }},
 {id:'profiles',run:function(ctx){
   /* Chaque profil, actif ou non : bloc lisible, clés intactes ; la voix de chaque profil prend
      la valeur réellement entendue (Android), car celle de la page n'a jamais été utilisée sur le poste. */
   var list=[];try{list=JSON.parse(get('profiles','[]'))||[]}catch(e){throw new Error('liste des profils illisible')}
   var v=A?androidVoice():null,n=0;
   list.forEach(function(p){
     if(!p||!p.id||p.id==='guest')return;
     var k='pfSnap_'+p.id,raw=get(k,null);if(raw===null)return;   // profil jamais quitté : ses réglages sont les clés actives
     var s;try{s=JSON.parse(raw)}catch(e){throw new Error('profil '+p.id+' illisible')}
     ctx.keep(k);
     var ch=false;
     if(v){if(s.vOn!==undefined&&s.vOn!==v.on){s.vOn=v.on;ch=true}if(s.vVol!==undefined&&s.vVol!==v.vol){s.vVol=v.vol;ch=true}}
     if(ch){put(k,JSON.stringify(s));n++}
   });
   return list.length+' profil(s), '+n+' mis à jour';
 }}
];

/* ---------- Vérification : rien ne doit avoir disparu ---------- */
function verify(before){
  var after=allLocal(),err=[];
  Object.keys(before).forEach(function(k){if(!(k in after))err.push('clé disparue : '+k)});
  Object.keys(after).forEach(function(k){if(/^pfSnap_/.test(k)){try{JSON.parse(after[k])}catch(e){err.push('profil illisible : '+k)}}});
  try{JSON.parse(after.profiles||'[]')}catch(e){err.push('liste des profils illisible')}
  Object.keys(before).forEach(function(k){if(/^pfSnap_/.test(k)){var a=Object.keys(JSON.parse(before[k])).length,b=Object.keys(JSON.parse(after[k]||'{}')).length;if(b<a)err.push('profil incomplet : '+k)}});
  return err;
}

/* ---------- Migration : sauvegarde -> étapes notées -> vérification -> retour si échec ---------- */
C.migrate=function(opt){
  opt=opt||{};
  var cur=parseInt(get('cfgV','0'),10)||0;
  if(cur>=TARGET)return {status:'à jour',cfgV:cur};
  if((parseInt(get('cfgFail','0'),10)||0)>=2)return {status:'suspendue',raison:'2 échecs : l\'ancien état est gardé, voir le journal'};
  var j;try{j=JSON.parse(get('cfgMig','null'))}catch(e){j=null}
  if(!j||j.target!==TARGET){
    var bk=backupFull('avant migration v'+cur+' vers v'+TARGET);
    if(!bk.ok){log('migration annulée : sauvegarde impossible');return {status:'annulée',raison:'sauvegarde impossible'}}
    j={target:TARGET,from:cur,backup:bk.id,done:[],before:null,start:new Date().toISOString()};
    j.before=allLocal();delete j.before.cfgMig;delete j.before.cfgLog;
    put('cfgMig',JSON.stringify(j));log('sauvegarde '+bk.id+' ('+bk.size+' caractères)');
  }else log('reprise d\'une migration interrompue');
  var kept={};var ctx={keep:function(k){var o='cfgOld_'+k;if(get(o,null)===null&&get(k,null)!==null)put(o,get(k,null));kept[k]=1}};
  try{
    for(var i=0;i<STEPS.length;i++){
      var s=STEPS[i];if(j.done.indexOf(s.id)>=0)continue;
      if(opt.stopAfter===i)throw {interrupt:true};                    // test : simule une coupure
      var r=s.run(ctx);j.done.push(s.id);put('cfgMig',JSON.stringify(j));log(s.id+' : '+r);
    }
    var err=verify(j.before);if(opt.forceFail)err.push('échec simulé');
    if(err.length)throw new Error(err.join(' ; '));
    put('cfgV',String(TARGET));LS.removeItem('cfgMig');log('migration v'+j.from+' vers v'+TARGET+' réussie');
    return {status:'réussie',cfgV:TARGET,backup:j.backup,anciennes:Object.keys(kept)};
  }catch(e){
    if(e&&e.interrupt)return {status:'interrompue'};
    log('échec : '+(e&&e.message||e)+' — retour à la sauvegarde '+j.backup);
    var ok=restoreFull(j.backup);put('cfgFail',String((parseInt(get('cfgFail','0'),10)||0)+1));
    return {status:'échec',raison:String(e&&e.message||e),restaure:ok};
  }
};
/* ---------- Fichier de sauvegarde v2 : tout, sans limite silencieuse ---------- */
var MAXV=5000000;   // garde-fou par valeur (5 millions de caractères) ; tout refus est signalé
var SKIP={page:1,init:1,trip:1,sun:1,cfgMig:1};   // états passagers, jamais restaurés (comme en 2.6)
function buildExport(opt){
  opt=opt||{};
  var nat=A&&A.getAllPrefs?call(function(){return JSON.parse(A.getAllPrefs())},{}):{};
  var o={app:'cockpit',kind:'export',v:2,date:new Date().toISOString(),cfgV:get('cfgV','0'),ls:allLocal(),prefs:nat,
         photos:A&&A.photoList?call(function(){return JSON.parse(A.photoList())},[]):[]};
  if(opt.photos!==false&&A&&A.photosBase64)o.photosData=call(function(){return JSON.parse(A.photosBase64())},{});
  return JSON.stringify(o);
}
function importText(txt){
  var o;try{o=JSON.parse(txt)}catch(e){return {ok:false,raison:'fichier illisible'}}
  var v1=o&&o.app==='golf6-accueil',v2=o&&o.app==='cockpit';
  if(!(v1||v2)||!o.ls||typeof o.ls!=='object')return {ok:false,raison:'ce fichier n\'est pas une sauvegarde Cockpit'};
  var rep={ok:true,version:v2?2:1,ecrites:0,refusees:[],photos:0,photosRefusees:[]};
  var bk=backupFull('avant restauration d\'un fichier');
  if(!bk.ok)return {ok:false,raison:'sauvegarde de sécurité impossible, restauration annulée'};
  Object.keys(o.ls).forEach(function(k){
    var val=o.ls[k];
    if(SKIP[k])return;                                              // passager : ignoré volontairement
    if(typeof val!=='string'){rep.refusees.push({cle:k,raison:'valeur non texte'});return}
    if(!/^[A-Za-z0-9_]{1,64}$/.test(k)){rep.refusees.push({cle:k,raison:'nom de clé invalide'});return}
    if(val.length>MAXV){rep.refusees.push({cle:k,raison:'trop grande ('+val.length+' caractères)'});return}
    if(put(k,val))rep.ecrites++;else rep.refusees.push({cle:k,raison:'mémoire du stockage pleine'});
  });
  if(v2){
    if(A&&o.prefs&&A.setAllPrefs){var okp=call(function(){return A.setAllPrefs(JSON.stringify(o.prefs))},false);if(okp===false)rep.refusees.push({cle:'préférences Android',raison:'écriture impossible'})}
    if(o.photosData&&A&&A.putPhotoBase64)Object.keys(o.photosData).forEach(function(n){
      if(call(function(){return A.putPhotoBase64(n,o.photosData[n])},false))rep.photos++;else rep.photosRefusees.push(n)});
  }else{
    /* Fichier 2.6 : la voix, le prénom et la luminosité réellement utilisés sont dans « native ».
       On les reprend dans la page (seule source en 3.0) et dans Android, et on aligne la voix de chaque profil. */
    var n=o.native||{};
    if(typeof n.voice==='string'){var sv=n.voice.split(','),on=sv[0]==='1'?'1':'0',vol=String(parseInt(sv[1],10)||90);
      put('vOn',on);put('vVol',vol);if(A&&A.setVoice)call(function(){A.setVoice(on==='1',parseInt(vol,10))});
      Object.keys(o.ls).forEach(function(k){if(!/^pfSnap_/.test(k))return;try{var p=JSON.parse(get(k,'{}'));if(p.vOn!==undefined)p.vOn=on;if(p.vVol!==undefined)p.vVol=vol;put(k,JSON.stringify(p))}catch(e){}})}
    if(typeof n.prenom==='string'&&n.prenom){put('prenom',n.prenom);if(A&&A.setPrenom)call(function(){A.setPrenom(n.prenom)})}
    if(n.bright!==undefined&&n.bright!==null&&n.bright!==''){var br=parseInt(n.bright,10)||80;put('bright',String(br));if(A&&A.setBrightness)call(function(){A.setBrightness(br)})}
    var lg=o.ls.lang||o.lang;if(lg&&A&&A.setLang)call(function(){A.setLang(lg)});
  }
  rep.securite=bk.id;
  log('restauration fichier v'+rep.version+' : '+rep.ecrites+' réglages, '+rep.photos+' photos, '+rep.refusees.length+' refus'+(rep.refusees.length?' ('+rep.refusees.map(function(r){return r.cle+' : '+r.raison}).join(' ; ')+')':'')+(rep.photosRefusees.length?', photos refusées : '+rep.photosRefusees.join(', '):''));
  return rep;
}
C.backup={build:buildExport,importText:importText,MAXV:MAXV};
C.log=log;
C.store={get:get,put:put,backupFull:backupFull,restoreFull:restoreFull,TARGET:TARGET,PHOTOS:PHOTOS};
})(typeof window!=='undefined'?window:globalThis);
