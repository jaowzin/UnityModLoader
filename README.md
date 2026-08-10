# Unity Mod Loader (prototype)

Primeira base de um loader/mod manager Android focado exclusivamente em jogos Unity.

## V0.1

- Seleciona um APK pelo Storage Access Framework (sem pedir acesso geral ao armazenamento).
- Detecta `libunity.so`.
- Detecta `libil2cpp.so` e classifica Unity IL2CPP.
- Detecta assemblies `.dll` em `Managed/` e classifica Unity Mono.
- Detecta `global-metadata.dat`.
- Lista ABIs encontradas (`arm64-v8a`, `armeabi-v7a`, etc.).
- Cria uma estrutura privada de `mods/plugins` e `mods/config`.
- Inclui um core C++/JNI mínimo (`umlcore`).
- Inclui GitHub Actions para gerar o APK debug.

## O que ainda NÃO faz

Esta versão não injeta código em outros aplicativos, não altera APKs e não contorna a sandbox do Android. O backend de execução de plugins será um módulo separado por estratégia (IL2CPP/Mono) e deve ser usado apenas em jogos/testes em que você tenha autorização para modificar.

## Build no GitHub

1. Suba este projeto para um repositório GitHub.
2. Abra **Actions**.
3. Execute **Build Unity Mod Loader**.
4. Baixe o artifact `UnityModLoader-debug`.

O workflow usa AGP 9.1.1, Gradle 9.3.1, JDK 17, SDK 36 e NDK 28.2.13676358.

## Próxima etapa

Criar a API de plugins e dois contratos de backend:

- `Il2CppBackend`
- `MonoBackend`

A V0.1 mantém detecção, UI e core separados justamente para permitir trocar a técnica de carregamento sem reescrever o aplicativo.
