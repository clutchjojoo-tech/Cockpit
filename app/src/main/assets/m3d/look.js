/* Cockpit 3.0 — m3d/look.js : apparence de la voiture 3D (showrooms, LED, carrosserie, plaque).
   Données seules + fabrication de l'image de plaque. Le moteur m3p.js les lit ; l'interface les affiche.
   Couleurs de LED mesurées dans les images des showrooms (13/11/2026), vert adouci à la demande de Jonathan. */
(function(){
'use strict';
var SHOWROOMS=[
  /* id, nom affiché, moment, image, couleur des LED, intensité des LED */
  {id:'actuel_nuit',nom:'Actuel',moment:'nuit',img:'m3d/sr/actuel_nuit.webp',led:'#1e6ffe',gain:1},
  {id:'rouge_nuit',nom:'Rouge',moment:'nuit',img:'m3d/sr/rouge_nuit.webp',led:'#ff1915',gain:1},
  {id:'rose_nuit',nom:'Rose',moment:'nuit',img:'m3d/sr/rose_nuit.webp',led:'#ff1797',gain:1},
  {id:'orange_nuit',nom:'Orange',moment:'nuit',img:'m3d/sr/orange_nuit.webp',led:'#ff6e00',gain:1},
  {id:'vert_nuit',nom:'Vert',moment:'nuit',img:'m3d/sr/vert_nuit.webp',led:'#3dff6e',gain:1},
  {id:'actuel_jour',nom:'Actuel',moment:'jour',img:'m3d/sr/actuel_jour.webp',led:'#ffb347',gain:.8},
  {id:'rouge_jour',nom:'Rouge',moment:'jour',img:'m3d/sr/rouge_jour.webp',led:'#ff0008',gain:.8},
  {id:'rose_jour',nom:'Rose',moment:'jour',img:'m3d/sr/rose_jour.webp',led:'#ff08da',gain:.8},
  {id:'orange_jour',nom:'Orange',moment:'jour',img:'m3d/sr/orange_jour.webp',led:'#ff7522',gain:.8},
  {id:'vert_jour',nom:'Vert',moment:'jour',img:'m3d/sr/vert_jour.webp',led:'#3dff6e',gain:.8}
];
/* Teintes Volkswagen d'origine de la Golf 6 (codes relevés au catalogue 2010 ; teinte affichée = approximation à l'écran) */
var PEINTURES=[
  {code:'LA7W',nom:'Argent Reflex',hex:'#c9cdd3'},
  {code:'LB9A',nom:'Blanc Candy',hex:'#f1f1ee'},
  {code:'LC9X',nom:'Noir intense nacré',hex:'#121316'},
  {code:'L041',nom:'Noir uni',hex:'#0b0b0c'},
  {code:'LD7X',nom:'Gris Platinum',hex:'#8e9298'},
  {code:'LA7T',nom:'Gris United',hex:'#5e6369'},
  {code:'LE7W',nom:'Gris Vulcan',hex:'#4c4f54'},
  {code:'LY3D',nom:'Rouge Tornado',hex:'#b5121b'},
  {code:'LA3S',nom:'Rouge Amaryllis',hex:'#7c1420'},
  {code:'LD5Q',nom:'Bleu Shadow',hex:'#1f3554'},
  {code:'LR5Z',nom:'Bleu Rising',hex:'#2b5d8f'},
  {code:'LA5Q',nom:'Bleu Shark',hex:'#47698a'},
  {code:'LC5F',nom:'Bleu Graphite nacré',hex:'#2a3446'},
  {code:'LR7L',nom:'Or blanc',hex:'#b9ad94'}
];
/* Teintes Peugeot d'origine de la 207 (codes constructeur ; teinte affichée = approximation à l'écran) */
var PEINTURES_207=[
  {code:'EZR',nom:'Gris Aluminium',hex:'#b7bbbe'},
  {code:'EWP',nom:'Blanc Banquise',hex:'#f2f3f0'},
  {code:'EXL',nom:'Noir Obsidien',hex:'#101113'},
  {code:'KTV',nom:'Noir Perla Nera',hex:'#15151a'},
  {code:'EZW',nom:'Gris Fer',hex:'#5f6367'},
  {code:'KTH',nom:'Gris Thorium',hex:'#4a4e53'},
  {code:'EKQ',nom:'Rouge Lucifer',hex:'#b3141c'},
  {code:'KKN',nom:'Rouge Aden',hex:'#8b1a20'},
  {code:'KPL',nom:'Bleu Montebello',hex:'#1d2b4a'},
  {code:'KMF',nom:'Bleu Égée',hex:'#2f6896'},
  {code:'KHW',nom:'Orange Salamanque',hex:'#cf5f1d'}
];
var BASE_PAINT='#c9cdd3';   // teinte des images calculées (Argent Reflex)
/* voiture choisie (par profil) : golf6 ou p207 */
/* 3.0 : les voitures 3D se téléchargent (packs). Une voiture n'est utilisée que si son pack est sur le poste. */
var CARLIST=[{id:'golf6',nom:'Volkswagen Golf 6',court:'Golf 6',info:'5 portes',mo:38},{id:'p207',nom:'Peugeot 207',court:'207',info:'3 portes',mo:20}];
function carInstalled(c){var A=window.Android;if(!A||!A.carStatus)return !!(window.__carDev&&window.__carDev[c]);try{return !!JSON.parse(A.carStatus(c)).installed}catch(e){return false}}
var _carC={t:0,v:{}};function carOk(c){var n=Date.now();if(n-_carC.t>3000){_carC={t:n,v:{}}}if(!(c in _carC.v))_carC.v[c]=carInstalled(c);return _carC.v[c]}
/* voiture affichée : celle choisie si elle est là, sinon la première téléchargée, sinon aucune (accueil en 2D) */
function car(){var c=get('m3dCar','golf6');if(carOk(c))return c;for(var i=0;i<CARLIST.length;i++)if(carOk(CARLIST[i].id))return CARLIST[i].id;return null}
function paints(c){return (c||car())==='p207'?PEINTURES_207:PEINTURES}
var CARNOMS={golf6:'Volkswagen Golf 6',p207:'Peugeot 207'};

function get(k,d){try{var v=localStorage.getItem(k);return v===null?d:v}catch(e){return d}}
function byId(id){for(var i=0;i<SHOWROOMS.length;i++)if(SHOWROOMS[i].id===id)return SHOWROOMS[i];return null}
function showroom(jour){var s=byId(get(jour?'sroomJ':'sroomN',jour?'actuel_jour':'actuel_nuit'));if(!s||(s.moment==='jour')!==!!jour)s=byId(jour?'actuel_jour':'actuel_nuit');return s}
function paint(){var v=get('m3dCol',car()==='p207'?PEINTURES_207[0].hex:BASE_PAINT);return /^#[0-9a-f]{6}$/i.test(v)?v:BASE_PAINT}

/* ---------- plaque d'immatriculation (dessinée une fois dans un canvas, puis envoyée à la carte graphique) ---------- */
var PL={W:520,H:174};   // taille de la texture de plaque (proportions du modèle 2172×724 recadré)
var atlas=null,atlasJ=null,imgs={},deps={};
function img(src){if(imgs[src])return imgs[src];var i=new Image();i.decoding='async';i.src=src;imgs[src]=i;return i}
function ready(i){return i&&i.complete&&i.naturalWidth>0}
function plateStyle(){var s=get('plStyle','bleue');return s==='noire'||s==='masquee'?s:'bleue'}
function plateText(){var t=String(get('plNum','')).toUpperCase().replace(/[^A-Z0-9-]/g,'');return t||'AB-123-CD'}
function plateDep(){var d=String(get('plDep',''));return /^(\d{2,3}|2A|2B)$/.test(d)?d:''}
var SIV=/^[A-HJ-NP-TV-Z]{2}-(?!000)\d{3}-[A-HJ-NP-TV-Z]{2}$/;   // format français (lettres I, O et U exclues)
function plateValid(t){return SIV.test(t)}
/* fabrique l'image de la plaque ; renvoie null tant que les fichiers ne sont pas chargés */
function plateCanvas(cv){
  var st=plateStyle();if(st==='masquee')return null;
  var tpl=img('m3d/plaque/'+(st==='noire'?'noire':'bleue')+'.webp'),at=img('m3d/plaque/police.png');
  if(!atlasJ){atlasJ='…';var x=new XMLHttpRequest();x.open('GET','m3d/plaque/police.json');x.onload=function(){try{atlas=JSON.parse(x.responseText)}catch(e){}};x.send()}
  if(!ready(tpl)||!ready(at)||!atlas)return null;
  var dep=plateDep(),sp=null;
  if(dep){var key=(st==='noire'?'n':'b');sp=img('m3d/plaque/dep_'+key+'.webp');if(!ready(sp))return null}
  cv=cv||document.createElement('canvas');cv.width=PL.W;cv.height=PL.H;var c=cv.getContext('2d');c.clearRect(0,0,PL.W,PL.H);
  /* modèle 2172×724 : plaque utile de x16 à 2156, y136 à 593 */
  var sx=PL.W/2140,sy=PL.H/457;c.drawImage(tpl,0,0,PL.W,PL.H);   // modèle déjà recadré sur la plaque utile
  function X(v){return (v-16)*sx}function Y(v){return (v-136)*sy}
  /* texte noir centré dans la zone blanche */
  var t=plateText(),h=(550-185)*0.74*sy,s=h/atlas.h,gap=0.12*h,w=0,G=[];
  for(var i=0;i<t.length;i++){var g=atlas.g[t[i]];if(!g)continue;G.push(g);w+=g[1]*s}
  w+=gap*Math.max(0,G.length-1);var zx0=X(280),zx1=X(1895),k=Math.min(1,(zx1-zx0)/w),x0=zx0+((zx1-zx0)-w*k)/2,y0=Y(185)+((Y(550)-Y(185))-h*k)/2;
  /* le masque de la police est en niveaux de gris : on le teinte en noir sur un canvas intermédiaire */
  var tc=document.createElement('canvas');tc.width=Math.ceil(w*k)+2;tc.height=Math.ceil(h*k)+2;var tx=tc.getContext('2d'),xx=0;
  G.forEach(function(g){tx.drawImage(at,g[0],0,g[1],atlas.h,xx,0,g[1]*s*k,h*k);xx+=(g[1]*s+gap)*k});
  tx.globalCompositeOperation='source-in';tx.fillStyle='#111';tx.fillRect(0,0,tc.width,tc.height);
  c.drawImage(tc,x0,y0);
  if(dep&&sp&&deps.idx){var e=deps.idx[(st==='noire'?'n':'b')+'_'+dep];
    if(e){var bx0=X(1912),bx1=X(2124),by0=Y(170),by1=Y(566);c.fillStyle=st==='noire'?'#050505':'#0a39a6';
      c.beginPath();c.rect(bx0,by0,bx1-bx0,by1-by0);c.fill();
      var kk=Math.min((bx1-bx0-3)/e[2],(by1-by0-3)/e[3]);c.drawImage(sp,e[0],e[1],e[2],e[3],bx0+((bx1-bx0)-e[2]*kk)/2,by0+((by1-by0)-e[3]*kk)/2,e[2]*kk,e[3]*kk)}
    else{/* pas de badge pour ce département (absent ou faux dans les planches d'origine) : le numéro seul, en blanc */
      var cx=(X(1912)+X(2124))/2;c.fillStyle='#fff';c.font='bold '+Math.round(PL.H*.3)+'px sans-serif';c.textAlign='center';c.textBaseline='middle';c.fillText(dep,cx,Y(470))}}
  return cv}
(function(){var x=new XMLHttpRequest();x.open('GET','m3d/plaque/dep.json');x.onload=function(){try{deps.idx=JSON.parse(x.responseText)}catch(e){}};x.send()})();

/* départements : numéro → région (texte écrit par Cockpit dans le menu, sans l'image d'origine) */
var REGIONS={'Auvergne-Rhône-Alpes':'01 03 07 15 26 38 42 43 63 69 73 74','Bourgogne-Franche-Comté':'21 25 39 58 70 71 89 90','Bretagne':'22 29 35 56',
 'Centre-Val de Loire':'18 28 36 37 41 45','Corse':'2A 2B','Grand Est':'08 10 51 52 54 55 57 67 68 88','Hauts-de-France':'02 59 60 62 80',
 'Île-de-France':'75 77 78 91 92 93 94 95','Normandie':'14 27 50 61 76','Nouvelle-Aquitaine':'16 17 19 23 24 33 40 47 64 79 86 87',
 'Occitanie':'09 11 12 30 31 32 34 46 48 65 66 81 82','Pays de la Loire':'44 49 53 72 85',"Provence-Alpes-Côte d'Azur":'04 05 06 13 83 84',
 'Guadeloupe':'971','Martinique':'972','Guyane':'973','La Réunion':'974','Mayotte':'976','Saint-Pierre-et-Miquelon':'975','Saint-Barthélemy':'977',
 'Saint-Martin':'978','Wallis-et-Futuna':'986','Polynésie française':'987','Nouvelle-Calédonie':'988'};
var DEPS=[];Object.keys(REGIONS).forEach(function(r){REGIONS[r].split(' ').forEach(function(d){DEPS.push({d:d,r:r})})});
DEPS.sort(function(a,b){return a.d<b.d?-1:a.d>b.d?1:0});

window.CockpitLook={SHOWROOMS:SHOWROOMS,PEINTURES:PEINTURES,PEINTURES_207:PEINTURES_207,car:car,paints:paints,carInstalled:carInstalled,CARLIST:CARLIST,carRefresh:function(){_carC={t:0,v:{}}},CARNOMS:CARNOMS,BASE_PAINT:BASE_PAINT,DEPS:DEPS,
  showroom:showroom,byId:byId,paint:paint,plateStyle:plateStyle,plateText:plateText,plateDep:plateDep,plateValid:plateValid,plateCanvas:plateCanvas,PL:PL,
  hasBadge:function(d,st){return !!(deps.idx&&deps.idx[(st==='noire'?'n':'b')+'_'+d])}};
})();
