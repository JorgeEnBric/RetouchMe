# Retouch Me

Aplicación Android para intercambio de rostros y retoque fotográfico, sin rostros generados por IA aburridos.
Mejorando tus fotos con tu propio y autentico rostro, utilizando modelos de ML para detección facial, alineamiento geométrico y blending avanzado.

## Características principales

- **Perfiles faciales** — Crea perfiles con nombre y agrega 4-12+ fotos de la misma persona. La app detecta y recorta los rostros automáticamente.
- **Face Swap automático** — Selecciona una imagen objetivo, toca un rostro para reemplazarlo y elige un perfil. El swap se realiza con alineamiento geométrico y blending Laplaciano.
- **Editor manual** — Después del swap automático, accede a herramientas de edición:
  - Zoom / Escala (pinch-to-zoom + slider)
  - Posición (desplazamiento X/Y)
  - Rotación del rostro
  - Borrador de halo (pincel con deshacer)
  - Copiar tono de piel (muestreo + pintura)
  - Frame Expand (expandir/contraer la máscara de contorno)
  - Magic Halo (máscara automática con MediaPipe 468 puntos)
  - Suavizado de piel (efecto porcelana)
- **Post-procesamiento** — Ajustes por región facial y globales: brillo, contraste, saturación, gamma, calidez y nitidez.
- **Guardado** — El resultado final se guarda como JPEG en `Pictures/Retouch Me`.

## Capturas

<table>
      <tr>
    <td align="center">
      <img width="60%" height="60%" alt="1785076187106" src="https://github.com/user-attachments/assets/b2da01e2-6e0f-4871-9f8a-86849670ca00" />
    </td>
    <td align="center">
      <img width="60%" height="60%" alt="1785076187139" src="https://github.com/user-attachments/assets/b5d06b25-1c02-4f8c-9d00-5985da744a7e" />
    </td>
  </tr>
      <tr>
    <td align="center">
      <img width="50%" height="50%" alt="1785076187184" src="https://github.com/user-attachments/assets/ecfe5f0f-ff05-47b8-b35c-6320a9cb6a87" />
    </td>
    <td align="center">
      <img width="50%" height="50%" alt="1785076187128" src="https://github.com/user-attachments/assets/c22d2c69-35e6-40bf-978c-d8eee8f3894e" />
    </td>
  </tr>
    <tr>
    <td align="center">
      <img width="50%" height="50%" alt="1785076187184" src="https://github.com/user-attachments/assets/ecfe5f0f-ff05-47b8-b35c-6320a9cb6a87" />
    </td>
    <td align="center">
      <img width="50%" height="50%"alt="1785076187122" src="https://github.com/user-attachments/assets/7501d2f3-e8d6-4876-84cd-29e9901a5be7" />
    </td>
  </tr>
</table>

## Tecnologías

| Categoría | Tecnología | Versión |
|---|---|---|
| Lenguaje | Kotlin | 2.0.21 |
| Build | Gradle (Kotlin DSL), AGP | 8.9.0 |
| Min SDK | Android 7.0 (API 24) | — |
| Target/Compile SDK | Android 15 (API 35) | — |
| UI | Material Design 3, Navigation Component, ViewBinding | Material 1.13.0 |
| Base de datos | Room | 2.7.1 |
| Detección facial | Google ML Kit Face Detection | 16.1.7 |
| Face Mesh | MediaPipe Tasks Vision | 0.10.0 |
| Embedding facial | OpenFace (nn4.small2.v1) vía ONNX Runtime | 1.18.0 |
| Procesamiento de imagen | OpenCV (Android) | 4.13.0 |
| Carga de imágenes | Coil | 2.7.0 |
| Asincronía | Kotlin Coroutines | 1.10.1 |
| Arquitectura | ViewModel, LiveData, Flow, Repository | Lifecycle 2.10.0 |

## Modelos ML

| Modelo | Formato | Propósito |
|---|---|---|
| **OpenFace (nn4.small2.v1)** | ONNX (`openface.onnx`) | Genera embeddings de identidad de 128 dimensiones para encontrar la referencia más representativa de un perfil. |
| **MediaPipe Face Landmarker** | Task file (`face_landmarker.task`) | Extrae 468 puntos faciales para construir contornos precisos (convex hull) para la máscara Magic Halo. |
| **ML Kit Face Detection** | Google Play Services | Detección facial principal: bounding boxes, landmarks y contornos para alineamiento de 5/6 puntos. |

## Estructura del proyecto

```
app/src/main/java/com/example/retake_lite/
├── App.kt                              # Inicialización de OpenCV
├── MainActivity.kt                     # Navegación principal
├── data/                               # Capa Room (entities, DAO, repository)
├── face/                               # Motor de procesamiento facial
│   ├── FaceDetectorHelper.kt           # Wrapper ML Kit
│   ├── FaceEmbedder.kt                 # Embeddings OpenFace vía ONNX
│   ├── FaceAligner.kt                  # Alineamiento afín a plantilla 96x96
│   ├── FaceSwapEngine.kt              # Fachada de alto nivel: swap automático
│   ├── FaceRetakeEngine.kt            # Pipeline de dos fases: cálculo + render
│   ├── FaceMaskBuilder.kt             # Máscaras, convex hull, blending Laplaciano/Poisson
│   ├── MediaPipeFaceMeshHelper.kt     # MediaPipe 468 puntos
│   ├── LabColorTransfer.kt            # Transferencia de color LAB (Reinhard)
│   └── PostProcessEngine.kt           # Ajustes a nivel de píxel
├── ui/                                 # Capa de interfaz
│   ├── home/                           # Pantalla de bienvenida
│   ├── profile/                        # Gestión de perfiles + fotos
│   ├── swap/                           # Pantalla principal de face swap
│   ├── edit/                           # Editor manual + post-procesamiento
│   └── contour/                        # Test de contornos (debug)
└── util/
    └── BitmapUtils.kt                  # Utilidades de carga de Bitmap
```

## Arquitectura

- **Pipeline de dos fases** — El swap se divide en `computeAutoResult()` (detección + alineamiento + matriz) y `render()` (re-aplicado por cambio de slider sin re-detectar). Los ajustes de slider son prácticamente instantáneos.
- **Sesiones en memoria** — `RetakeEditSession` y `PostProcessSession` mantienen referencias al motor y bitmaps entre actividades, superando el límite de tamaño de Binder para intents.
- **Blending** — Mezcla Laplaciano con alpha feathered + clonado Poisson/seamless opcional vía OpenCV.
- **Transferencia de color** — Transferencia estadística en espacio LAB (estilo Reinhard) que ajusta la crominancia del rostro pegado al tono de piel circundante.

## Requisitos

- Android Studio Ladybug (2024.2) o superior
- JDK 11+
- Dispositivo o emulador con Android 7.0+ (API 24)

## Instalación

```bash
git clone https://github.com/tu-usuario/Retake_lite.git
cd Retake_lite
./gradlew assembleDebug
```

El APK se generará en `app/build/outputs/apk/debug/`.

## Permisos

- `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` — Acceso a galería para seleccionar imágenes.
- `WRITE_EXTERNAL_STORAGE` — Guardar resultado (pre-Android 10).

## Licencia

MIT License. Ver [LICENSE](LICENSE) para más detalles.
