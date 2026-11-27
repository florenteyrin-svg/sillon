# Sillon

Serveur de musique auto-hébergé, simple et rapide, avec son appli Android (à venir).

- **Lit ta bibliothèque telle quelle** : FLAC, ALAC, MP3, AAC, Opus, WAV, AIFF, WavPack, APE, DSD…
  Tes fichiers ne sont jamais modifiés (dossier monté en lecture seule).
- **Ajout instantané** : un morceau qui arrive dans le dossier (par exemple téléchargé par à la carte)
  est dans la bibliothèque en ~2 secondes, sans scan à attendre.
- **Logo de qualité** pour chaque titre et album : `hires` (plus de 16 bit ou plus de 48 kHz),
  `cd` (16 bit / 44,1 kHz, 1411 kbps) ou `lossy` (MP3, AAC…).
- **Compatible Subsonic / OpenSubsonic** : Music Assistant (donc Home Assistant), Symfonium, etc.
- **Recherche et téléchargement Apple Music** via à la carte, sans exposer à la carte sur Internet.
- **Playlists intelligentes** calculées en quelques millisecondes.
- **Reprise de lecture** sur n'importe quel appareil (file d'attente et position gardées par le serveur).
- Léger : ~30 Mo de RAM pour 22 000 titres.

## Installation (ZimaOS ou tout Linux avec Docker)

1. Récupère le projet dans le dossier où tu ranges tes compose :
   ```bash
   git clone https://github.com/florenteyrin-svg/sillon.git
   ```
2. Copie le fichier de réglages et remplis-le :
   ```bash
   cd sillon && cp .env.example .env && nano .env
   ```
   - `MUSIC_PATH` : ton dossier de musiques.
   - `SILLON_DATA_PATH` / `ALACARTE_DATA_PATH` : où garder les bases et réglages.
   - `SILLON_ADMIN_PASSWORD` : le mot de passe de ton compte Sillon.
3. Lance :
   ```bash
   docker compose up -d
   ```
   L'image de Sillon est téléchargée toute prête. À la carte est construit depuis son dépôt
   (quelques minutes la première fois).
4. Configure à la carte une seule fois sur `http://IP-DU-SERVEUR:7373` (compte, identifiants Apple),
   puis mets ce compte dans `ALACARTE_USER` / `ALACARTE_PASSWORD` du `.env` et relance
   `docker compose up -d`.

Mettre à jour :

```bash
docker compose pull && docker compose up -d --build
```

### Nginx Proxy Manager : un seul domaine

Un seul « Proxy Host » : ton domaine → `IP-DU-SERVEUR`, port `4747`, avec SSL.
À la carte n'a pas besoin de domaine : l'appli passe par Sillon pour chercher et télécharger.

### Music Assistant

Ajoute le fournisseur de musique **Open Subsonic** :

| Champ | Valeur |
|---|---|
| Adresse | `http://IP-DU-SERVEUR` (ou ton domaine en `https://`) |
| Port | `4747` (ou `443` derrière Nginx) |
| Utilisateur / mot de passe | ceux de `SILLON_ADMIN_USER` / `SILLON_ADMIN_PASSWORD` |

## Pour les curieux

- [docs/architecture.md](docs/architecture.md) : comment c'est construit, l'API, la suite.
- Le serveur est écrit en Go (`server/`), une base SQLite dans le dossier de données.

## Feuille de route

1. ~~Serveur : bibliothèque, ajout instantané, Subsonic, à la carte, playlists intelligentes~~
2. Appli Android (Kotlin) : démarrage instantané, Android Auto, widget vinyle, logo qualité,
   envoi vers Music Assistant, playlists hors ligne
3. Mix DJ (bouton) : tempo, tonalité, transitions vocales préparées par le serveur
4. Appli Windows, carte simple pour le mur Home Assistant
