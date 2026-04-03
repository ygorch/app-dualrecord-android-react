# DualRecord App (React Native & Android Native)

Um aplicativo avançado de gravação de vídeo para Android que permite a captura simultânea utilizando múltiplas lentes do dispositivo (ex: Principal e Ultrawide), gerando arquivos independentes em proporções diferentes (16:9 e 9:16) em tempo real.

Este projeto utiliza uma **Arquitetura Híbrida**, onde o **React Native** entrega uma UI Premium e reativa, enquanto **Módulos Nativos customizados em Kotlin** lidam com as pesadas restrições de I/O e hardware do Android (Camera2 API, MediaCodec, MediaMuxer).

## 🚀 Principais Funcionalidades

* **Gravação Dupla Simultânea:** Captura de duas lentes traseiras (Physical Cameras) através de uma única Sessão Lógica (Logical Multi-Camera API).
* **Saídas Independentes:** Multiplexação para dois arquivos MP4 separados (um em 16:9 e outro em 9:16) preservando o ISP do aparelho.
* **Single Audio Engine:** Captura centralizada de áudio PCM com injeção síncrona nos dois `MediaMuxers` para garantir que o áudio não dessincronize do vídeo.
* **Premium HUD:** Interface "Glassmorphism" construída com React Native, oferecendo layouts interativos em *Picture-in-Picture (PiP)* e *Split-Screen* sem distorção (Center Crop absoluto).
* **Hardware Fallback:** Degradação graciosa gerenciada nativamente. Se o dispositivo não suportar concorrência múltipla nativa (falta de banda no ISP), o app opera automaticamente em modo de câmera única.

## 🏗️ Arquitetura

O projeto contorna as limitações de bibliotecas prontas de JavaScript ao delegar todo o trabalho de hardware para o Kotlin:

1. **Frontend (React Native / TypeScript):**
   - Gerencia a máquina de estados complexa (Exclusão Mútua de lentes).
   - Renderiza a `DualCameraView` (Native UI Component).
   - Executa animações de UI (Reanimated) de layout PiP e Split-Screen.
2. **Backend (Android Native / Kotlin):**
   - **`DualCameraEngineModule`:** A ponte (Turbo Module) que expõe métodos como `startRecording` e `stopRecording` para o JS, implementando rotinas rigorosas de *Safe Teardown* para evitar *IllegalStateExceptions*.
   - **`DualCameraCaptureManager`:** Orquestrador da `Camera2` API focado em identificar `LogicalMultiCameras` e separar streams físicos usando `setPhysicalCameraId`.

## 🛠️ Pré-requisitos

Para rodar este projeto localmente, seu ambiente precisa estar configurado para o desenvolvimento React Native com Android Nativo moderno:

* **Node.js** (v20 ou superior)
* **Yarn** ou **npm**
* **Java Development Kit (JDK):** Versão 17 ou 21 recomendada.
* **Android Studio:** Configurado com Android SDK 34+.
* **Dispositivo Físico:** Devido ao uso agressivo da Câmera e do Hardware Composer (HWC), **emuladores não são suportados**. Use um aparelho premium moderno (ex: OnePlus 10 Pro, Pixel 7+, Samsung Galaxy S22+).

## 💻 Instalação e Execução

1. Clone o repositório:
   ```bash
   git clone [https://github.com/ygorch/app-dualrecord-android.git](https://github.com/ygorch/app-dualrecord-android.git)
   cd app-dualrecord-android
