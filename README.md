# Visualizador 3D (Android)

App nativo para visualizar malhas e nuvens de pontos `.stl` e `.ply` no celular.

## Recursos

- STL binário e ASCII
- PLY ASCII, binário little endian e big endian (malha ou nuvem de pontos, com normais e cores por vértice)
- Modos de exibição: Sólido, Arame e Pontos
- Gestos: 1 dedo gira, pinça dá zoom, 2 dedos movem, toque duplo reenquadra
- Mostra nome, formato, número de vértices e triângulos e as dimensões X, Y, Z (na unidade do arquivo, geralmente mm)
- Abre arquivos direto do gerenciador de arquivos, Drive ou WhatsApp ("Abrir com" ou "Compartilhar")
- Sem dependências externas, OpenGL ES 3.0, Android 7.0 ou superior

## Como gerar o APK

### Opção A: GitHub (sem instalar nada)

1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta.
2. Abra a aba **Actions**. O fluxo **Gerar APK** roda sozinho a cada envio (ou clique em **Run workflow**).
3. Ao terminar (cerca de 3 a 5 minutos), abra a execução e baixe o artefato **Visualizador3D-apk**.
4. Descompacte o .zip, copie `app-debug.apk` para o celular e instale (permita "instalar apps desconhecidos").

### Opção B: Android Studio

1. File > Open > selecione a pasta `Visualizador3D`.
2. Aguarde a sincronização do Gradle.
3. Build > Build App Bundle(s) / APK(s) > Build APK(s).
4. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Estrutura

| Arquivo | Função |
|---|---|
| `MeshLoader.java` | Leitura de STL e PLY |
| `BinReader.java` | Leitor bufferizado binário/texto |
| `Mesh.java` | Dados da malha, caixa envolvente e normais |
| `ModelRenderer.java` | Renderização OpenGL ES 3.0 e câmera orbital |
| `ModelView.java` | Gestos de toque |
| `MainActivity.java` | Interface e abertura de arquivos |
