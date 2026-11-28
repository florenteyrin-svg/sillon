# Modifications perso (par-dessus alacarte à jour du 30/10/2026)

- Onglet Library retiré (/library renvoie à l'accueil)
- Albums : Mes FLAC/<Artiste - Album>, Atmos : Mes Dolby Atmos, AAC : Mes MP3
- Playlists : Mes Playlists/<Nom>/NN. Titre.flac, tagguées comme un seul album avec la pochette de la playlist
- NOUVEAU : ce qu'octo-fiesta télécharge quand tu écoutes (Tempus, Symfonium) va dans « Mes Découvertes »
  - ces fichiers ne comptent pas comme « déjà dans la bibliothèque » dans alacarte
  - quand tu télécharges pour de vrai depuis alacarte, ça va dans Mes FLAC et la copie
    de Mes Découvertes est supprimée automatiquement (même ISRC)
  - nom du dossier : DISCOVERY_FOLDER dans backend/lib/folderLayout.mjs
- Mes Découvertes contient un fichier `.ignore` : Jellyfin ne l'affiche pas
- Les découvertes non gardées sont supprimées après 30 jours (variable AMDL_DISCOVERY_DAYS, 0 = jamais)
- API pour Allstarr : POST /api/integration/v1/{songs|albums|playlists}/:id/keep = vrai téléchargement (cœur)
- Pochettes animées (18/11/2026) : quand Apple en propose une, elle est enregistrée dans
  `<dossier de l'album>/cover/animated.mp4` (vidéo muette en boucle, H.264)
  - automatique après chaque téléchargement (album, titre, complétion d'album, découvertes)
  - bibliothèque existante : Réglages > Maintenance > « Récupérer les pochettes animées »
  - le dossier cover contient un `.ignore` : Jellyfin ne le scanne pas
  - code : backend/lib/animatedArtwork.mjs ; AMDL_ANIMATED_ARTWORK=0 pour désactiver
- Onglet Clips (18/11/2026) : chercher un clip ou un artiste, voir tous ses clips, télécharger
  - rangés dans `Mes Clips/<Artiste>/<Titre>.mp4` + vignette `<Titre>.jpg` (AMDL_CLIPS_FOLDER pour changer)
  - file à part (un clip à la fois), demande le media-user-token
  - Dockerfile : ajout de mp4decrypt (Bento4), nécessaire à amdp pour les clips
  - code : backend/lib/clips.mjs, backend/routes/clips.mjs, frontend/src/pages/Clips.tsx

- **Pochette animée dans la fiche album** (pour Sillon) : `GET /api/album/:id` renvoie aussi
  `album.animatedVideo`, l'adresse de la pochette animée d'Apple (playlist HLS), ou `null`.
- **Photo des artistes dans la recherche** (pour Sillon) : `GET /api/search` renvoie aussi
  `artworkTemplate` pour chaque artiste.
