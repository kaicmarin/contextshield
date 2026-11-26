# Validacao final V2 — 2026-11-26 01:31

| Resultado | Quantidade |
|-----------|------------|
| Aprovados | 16 |
| Reprovados | 0 |

## Evidencias R13
- Antes: REQUEST_CONTEXT / R5
- Depois publicar R13: BLOCK / R13
- Apos desativar: REQUEST_CONTEXT / R5

## ML
- Experimento: DONE
- Estudo pesos: True (sugestoes=10)
- Estudo regras: True

## Detalhe
[
    {
        "Id":  "FLY",
        "Test":  "Flyway V12-V14 (core)",
        "Pass":  true,
        "Detail":  "core ok=3"
    },
    {
        "Id":  "1a",
        "Test":  "Login admin",
        "Pass":  true,
        "Detail":  "token"
    },
    {
        "Id":  "1b",
        "Test":  "Login analista",
        "Pass":  true,
        "Detail":  "token"
    },
    {
        "Id":  "1c",
        "Test":  "Login cliente NEXA",
        "Pass":  true,
        "Detail":  "token"
    },
    {
        "Id":  "11a",
        "Test":  "Analista nao publica regra admin",
        "Pass":  true,
        "Detail":  "HTTP 403"
    },
    {
        "Id":  "2",
        "Test":  "R13 altera simulacao TX-184",
        "Pass":  true,
        "Detail":  "antes=REQUEST_CONTEXT/R5 depois=BLOCK/R13"
    },
    {
        "Id":  "3",
        "Test":  "Desativar R13 restaura",
        "Pass":  true,
        "Detail":  "dec=REQUEST_CONTEXT rule=R5"
    },
    {
        "Id":  "4",
        "Test":  "Publicar pesos dinamicos",
        "Pass":  true,
        "Detail":  "status=PUBLISHED"
    },
    {
        "Id":  "5",
        "Test":  "Treino ML experimento",
        "Pass":  true,
        "Detail":  "status=DONE"
    },
    {
        "Id":  "6",
        "Test":  "Estudo pesos + sugestoes",
        "Pass":  true,
        "Detail":  "study=True sugestoes=10"
    },
    {
        "Id":  "7",
        "Test":  "Estudo regras FP/FN",
        "Pass":  true,
        "Detail":  "insuficiente=True regras=0"
    },
    {
        "Id":  "8",
        "Test":  "Sugestao -\u003e rascunho sem auto-publish",
        "Pass":  true,
        "Detail":  "ruleDraft= weightStillDraft=True"
    },
    {
        "Id":  "9",
        "Test":  "Safety Monitor",
        "Pass":  true,
        "Detail":  "envelope=0"
    },
    {
        "Id":  "10",
        "Test":  "Aviso sintetico na UI/API",
        "Pass":  true,
        "Detail":  "limitation"
    },
    {
        "Id":  "INT",
        "Test":  "run-v2-integrated",
        "Pass":  true,
        "Detail":  "exit=0"
    },
    {
        "Id":  "12",
        "Test":  "E2E NEXA/Aureon/Ops",
        "Pass":  true,
        "Detail":  "exit=0"
    }
]
