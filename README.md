# Fondos de Parallax 3D

Cada carpeta dentro de `packs/` es un fondo. Al subir una carpeta nueva, GitHub arma el catálogo,
lo firma y lo publica solo. Las apps lo ven la próxima vez que se abren, sin actualizar nada.

## Agregar un fondo (desde la web de GitHub)

1. Entrá a la carpeta `packs/` del repositorio.
2. **Add file → Upload files** y arrastrá una carpeta con las capas, por ejemplo `aurora/`:
   - `1.png` es la capa del fondo (la de más atrás).
   - `2.png`, `3.png`… hasta `6.png` van hacia adelante. Las de adelante tienen que ser PNG con transparencia.
   - Podés usar de 1 a 6 capas. Con 1 capa la foto entera se mueve como una sola.
   - Opcional: un `info.json` con el nombre visible: `{ "name": "Aurora boreal" }`.
     Si no está, se usa el nombre de la carpeta.
3. **Commit changes**. En la pestaña **Actions** vas a ver "Publicar fondos" trabajando (1–2 minutos).
   Si termina en verde, ya está online.

Para **quitar** un fondo, borrá su carpeta. Para **cambiarlo**, reemplazá sus archivos.

### Reglas de las imágenes
- PNG o JPG, máximo 25 MB y 8192 px por lado. Se achican solas a 3000 px y se pasan a WebP.
- Tamaño recomendado: 1290 × 2796 (vertical, como la pantalla del teléfono).
- Si algo está mal, el fondo se saltea y aparece un aviso amarillo en Actions explicando qué pasó.

## Configuración (una sola vez)

1. Creá este repositorio en GitHub (puede ser **público**; las imágenes igual se publican).
2. **Settings → Secrets and variables → Actions → New repository secret**
   - Nombre: `CATALOG_SIGNING_KEY`
   - Valor: todo el contenido de `NO-SUBIR/catalog-signing-key.pem` (del proyecto de la app).
3. **Settings → Pages → Build and deployment → Source: GitHub Actions**.
4. Hacé un cambio en `packs/` (o en Actions → Publicar fondos → Run workflow).
5. La dirección queda como `https://TU-USUARIO.github.io/NOMBRE-DEL-REPO/`.
   Esa dirección va en `gradle.properties` de la app (`parallax.catalogUrl`).

## Seguridad

- La app solo acepta un catálogo firmado con tu clave privada. Aunque alguien entrara al hosting,
  no podría meter imágenes: la firma no coincidiría.
- Cada archivo tiene su huella SHA-256 en el catálogo; la app descarta cualquier archivo alterado.
- **La clave privada nunca se sube al repositorio.** Solo va en el secreto de GitHub.
  Si se pierde o se filtra, hay que generar otra y publicar una versión nueva de la app con la nueva clave pública.

Generar una clave nueva (Git Bash):

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out catalog-signing-key.pem
openssl ec -in catalog-signing-key.pem -pubout -outform DER | base64 -w0
```

La segunda línea imprime la clave pública que va en `parallax.catalogKey`.

## Probar en la computadora

```bash
java tools/BuildCatalog.java packs site
```

Genera la carpeta `site/` igual que en GitHub (sin firma).
