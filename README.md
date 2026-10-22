# Visualizador 3D (Android)

App nativo para visualizar malhas e nuvens de pontos `.stl` e `.ply` no celular.

## Recursos

- Formatos: STL (binário e ASCII), PLY (ASCII e binário, malha ou nuvem de pontos com normais e cores) e OBJ
- Arquivos recentes
- Vistas padrão (Iso, Frente, Topo, Direita, Esquerda, Trás, Baixo), perspectiva ou ortográfica, modos Sólido, Arame e Pontos
- Medição de distância (com ΔX, ΔY, ΔZ), diâmetro por 3 pontos e ajuste de primitivas (plano com planicidade, cilindro com Ø e eixo, esfera), com ângulo e distância entre elas
- Perfil 2D da seção com medição de distância e raio, exportado em DXF com coordenadas reais
- Plano de corte em X, Y ou Z
- Alinhamento XYZ: automático, por face plana, por aresta, giro fino e definição de origem; exporta a malha alinhada
- Análise: malha fechada ou aberta, bordas e furos em vermelho, ilhas, área, volume e massa estimada por material
- Comparação scan × CAD: alinhamento automático (eixos principais + ICP) ou refino, mapa de cores de desvio com tolerância ajustável, estatísticas e desvio por ponto
- Versão leve automática para girar malhas grandes com fluidez
- Modo apresentação com giro automático
- Espessura de parede em mapa de cores, com espessura mínima e leitura por ponto
- Orçamento de impressão 3D (FDM e resina) com parâmetros salvos e envio ao cliente
- Anotações 3D numeradas presas à peça, na foto e no relatório
- Relatório técnico em PDF com vistas, mapa de desvios, estatísticas, análise e medições
- Foto da tela com rodapé e envio de STL por WhatsApp

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
