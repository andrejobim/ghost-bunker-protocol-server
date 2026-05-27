# Ghost Bunker — Prompts Atualizados para Cursor IA

## Premissa geral

Estes prompts devem ser usados em sequência.

A separação correta é:

1. **Protocolo**: contrato de comunicação, mensagens, estados, erros e formato.
2. **Perfil de privacidade máxima**: regras de implementação para não persistir IP, payload, ciphertext, fingerprint ou identidade.
3. **Arquitetura de referência**: uma forma de implementar servidor/cliente usando o protocolo, sem confundir produto com protocolo.

O Ghost Bunker Protocol v0.1 não deve exigir:

- Registro de IP.
- Registro de payload.
- Registro de ciphertext.
- Fingerprint.
- Conta.
- Login.
- Cookie persistente.
- Identificador persistente.
- Bloqueio Tor/VPN no core do protocolo.
- Banco de dados.
- Histórico no servidor.

---

# Prompt 1 — Criar especificação pura do protocolo

```txt
Crie a especificação técnica final do "Ghost Bunker Protocol v0.1".

Objetivo:
Definir apenas o protocolo de comunicação, não a aplicação final que usará o protocolo.

Contexto:
- O protocolo será usado inicialmente para chat anônimo em tempo real.
- O protocolo deve ser independente de linguagem.
- O transporte será WebSocket sobre TLS, usando WSS.
- O payload WebSocket será binário.
- A serialização será Protobuf.
- O protocolo não usa gRPC.
- O protocolo não define banco de dados.
- O protocolo não define logs.
- O protocolo não define analytics.
- O protocolo não exige registro de IP.
- O protocolo não exige fingerprint.
- O protocolo não exige conta, login, e-mail ou telefone.
- O protocolo não exige bloqueio de Tor, VPN ou proxy.
- O protocolo deve permitir que uma implementação privacy-max não persista dados identificáveis.

Decisões da v0.1:
- Nome: Ghost Bunker Protocol
- Versão: 0.1
- Transporte: WebSocket sobre TLS
- Endpoint sugerido: wss://<host>/ghost-bunker
- Subprotocol sugerido: ghost-bunker.v0.1
- Serialização: Protobuf
- Framing: fornecido pelo próprio WebSocket
- Cada mensagem WebSocket binária contém exatamente um GhostEnvelope Protobuf
- Mensagens de chat trafegam como ciphertext
- Texto puro de chat nunca deve atravessar o WebSocket
- E2EE é obrigatório para mensagens de chat
- Criptografia v0.1: chave simétrica por sala
- Chave/passphrase da sala nunca é enviada ao servidor
- A aplicação cliente valida texto antes de criptografar
- O servidor/protocolo roteia ciphertext e metadata mínima

Separação obrigatória:
- O protocolo define mensagens, estados, erros, limites e compatibilidade.
- O protocolo não define a política operacional de logs da aplicação.
- O protocolo não define como a infraestrutura trata IP.
- O protocolo não define bloqueio de Tor/VPN/proxy.
- O protocolo não promete que IP nunca exista tecnicamente em TCP/WebSocket.
- O protocolo não deve incluir campo de IP, fingerprint ou device_id no GhostEnvelope.

A especificação deve conter:
1. Objetivo
2. Não objetivos
3. Transporte
4. Framing
5. Formato das mensagens
6. GhostEnvelope conceitual
7. Tipos de mensagem
8. Handshake HELLO/WELCOME
9. Estados do protocolo
10. Salas
11. Envio de mensagens criptografadas
12. Confirmação MESSAGE_ACCEPTED
13. Confirmação MESSAGE_RECEIVED_ACK
14. Heartbeat PING/PONG
15. Erros padronizados
16. Limites de envelope, ciphertext, nickname e room_id
17. Regras de compatibilidade Protobuf
18. Casos inválidos
19. Roadmap do protocolo

Tipos de mensagem obrigatórios:
- HELLO
- WELCOME
- JOIN_ROOM
- ROOM_JOINED
- LEAVE_ROOM
- ROOM_LEFT
- SEND_ENCRYPTED_MESSAGE
- MESSAGE_ACCEPTED
- ENCRYPTED_MESSAGE
- MESSAGE_RECEIVED_ACK
- PING
- PONG
- ERROR
- DISCONNECT
- GOODBYE

Regras importantes:
- Não usar nomes genéricos como SEND_MESSAGE ou MESSAGE para conteúdo de chat.
- Usar nomes explícitos com ENCRYPTED.
- Não incluir campo text no protocolo de transporte.
- Não incluir campo plaintext.
- Não incluir campo ip.
- Não incluir campo fingerprint.
- Não incluir campo device_id persistente.
- Não incluir autenticação de usuário na v0.1.
- session_id e user_id, se existirem, são efêmeros e emitidos pelo servidor.
- O servidor não deve confiar em session_id ou user_id enviados pelo cliente como credencial.
- A conexão WebSocket é a fonte de verdade da sessão enquanto estiver aberta.

Limites normativos da v0.1:
- Max envelope Protobuf: 64 KB
- Max plaintext antes da criptografia: 4 KB em UTF-8, validado no cliente oficial
- Max ciphertext por mensagem: 16 KB
- Max nickname: 32 caracteres
- Max room_id: 64 caracteres
- Max salas por conexão: 5
- Handshake timeout: 5 segundos
- Ping interval: 30 segundos
- Pong timeout: 10 segundos
- Idle timeout: 90 segundos

Criptografia:
- A v0.1 usa chave simétrica por sala.
- Suite obrigatória de referência: PBKDF2-HMAC-SHA256 + AES-256-GCM.
- Nonce/IV obrigatório por mensagem.
- Nonce recomendado: 12 bytes para AES-GCM.
- key_id obrigatório.
- enc_suite obrigatório.
- ciphertext obrigatório.
- O servidor não descriptografa.

Regra sem emoji:
- O protocolo não consegue validar emoji dentro de ciphertext.
- Clientes oficiais devem rejeitar emoji antes da criptografia.
- O servidor só valida campos em claro, como nickname e room_id.

Criar arquivo:
- docs/ghost-bunker-protocol-v0.1.md

Não implemente código neste passo.
```

---

# Prompt 2 — Criar perfil de privacidade máxima

```txt
Crie o documento "Ghost Bunker Privacy-Max Profile v0.1".

Objetivo:
Definir um perfil de implementação para aplicações que usam o Ghost Bunker Protocol e querem privacidade máxima.

Importante:
Este documento não é o protocolo em si. Ele é um perfil operacional recomendado para uma aplicação ou servidor de referência.

Criar arquivo:
- docs/privacy-max-profile-v0.1.md

Premissas:
- O protocolo não exige registro de IP.
- O protocolo não exige logs.
- O protocolo não exige fingerprint.
- O protocolo não exige analytics.
- O protocolo não exige conta.
- O protocolo não exige banco de dados.
- A aplicação que segue este perfil deve evitar persistência identificável.

O perfil deve exigir:
- Sem conta.
- Sem login.
- Sem e-mail.
- Sem telefone.
- Sem cookie persistente.
- Sem localStorage obrigatório.
- Sem fingerprint.
- Sem device_id persistente.
- Sem histórico no servidor.
- Sem persistência de IP.
- Sem persistência de payload.
- Sem persistência de ciphertext.
- Sem logs de mensagem.
- Sem analytics identificável.
- Sem request dump.
- Sem packet dump.
- Sem salvar headers de conexão.

Explicar o limite técnico:
- Em TCP/TLS/WebSocket, o IP existe tecnicamente durante a conexão ativa.
- O perfil não promete que o IP nunca exista na rede.
- O perfil exige que a aplicação não persista IP nem transforme IP em identidade duradoura.
- Infraestrutura como proxy reverso, CDN, firewall, load balancer, sistema operacional e provedor de hospedagem também precisam ser configurados para não registrar IP.

Antiabuso permitido no perfil:
- Rate limit por conexão.
- Limite de comandos por conexão.
- Limite de mensagens por conexão.
- Limite de salas por conexão.
- Limite de tamanho de payload.
- Backpressure.
- Fechamento por protocolo inválido repetido.
- Proof-of-work opcional no HELLO, sem identidade persistente.

Antiabuso que deve ficar fora do core privacy-max:
- Ban persistente por IP.
- Fingerprint de navegador.
- Device fingerprint.
- Lista local persistente de IPs.
- Bloqueio Tor/VPN/proxy dentro da aplicação core.

Observação:
- Bloqueio Tor/VPN/proxy pode existir como política opcional de uma aplicação ou infraestrutura, mas não deve ser requisito do protocolo nem do perfil privacy-max puro.

Incluir uma seção de configuração recomendada:
- Desabilitar access logs do servidor HTTP/WebSocket.
- Desabilitar logs de proxy reverso.
- Não logar headers.
- Não logar payload binário.
- Não logar remote address.
- Não expor exceptions com payload.
- Sanitizar logs técnicos.
- Métricas apenas agregadas e não identificáveis.

Não implemente código neste passo.
```

---

# Prompt 3 — Criar contrato Protobuf

```txt
Com base em docs/ghost-bunker-protocol-v0.1.md, crie o contrato Protobuf do Ghost Bunker Protocol v0.1.

Requisitos:
- Criar arquivo proto/ghost_bunker_v1.proto.
- Usar syntax = "proto3".
- Usar package ghostbunker.v1.
- O contrato deve ser independente de linguagem.
- Não implementar gRPC.
- Não criar service RPC.
- Deve existir um envelope principal chamado GhostEnvelope.
- Toda mensagem WebSocket binária deve carregar exatamente um GhostEnvelope serializado.

Campos comuns do GhostEnvelope:
- protocol
- version
- message_id
- timestamp_ms
- type
- request_id opcional
- room_id opcional
- payload usando oneof

Proibições:
- Não adicionar campo ip.
- Não adicionar campo remote_address.
- Não adicionar campo fingerprint.
- Não adicionar campo device_id persistente.
- Não adicionar campo text em mensagens trafegadas pelo servidor.
- Não adicionar campo plaintext.
- Não criar mensagens chamadas SendMessage ou ChatMessage com texto puro.

Mensagens necessárias:
- Hello
- Welcome
- JoinRoom
- RoomJoined
- LeaveRoom
- RoomLeft
- SendEncryptedMessage
- MessageAccepted
- EncryptedMessage
- MessageReceivedAck
- Ping
- Pong
- ErrorMessage
- Disconnect
- Goodbye

Enums necessários:
- MessageType
- ErrorCode
- DisconnectReason
- CipherSuite

Tipos MessageType:
- MESSAGE_TYPE_UNSPECIFIED
- HELLO
- WELCOME
- JOIN_ROOM
- ROOM_JOINED
- LEAVE_ROOM
- ROOM_LEFT
- SEND_ENCRYPTED_MESSAGE
- MESSAGE_ACCEPTED
- ENCRYPTED_MESSAGE
- MESSAGE_RECEIVED_ACK
- PING
- PONG
- ERROR
- DISCONNECT
- GOODBYE

Payload criptografado:
SendEncryptedMessage deve conter:
- client_message_id
- key_id
- cipher_suite
- nonce bytes
- ciphertext bytes
- aad_version opcional

EncryptedMessage deve conter:
- server_message_id
- from_user_id efêmero
- key_id
- cipher_suite
- nonce bytes
- ciphertext bytes
- sent_at_ms

Regras de criptografia:
- nonce deve ser bytes.
- ciphertext deve ser bytes.
- key_id não é segredo.
- cipher_suite deve indicar a suite usada.
- Suite obrigatória v0.1: PBKDF2_HMAC_SHA256_AES_256_GCM.

Hello:
- client_name opcional.
- nickname opcional.
- client capabilities opcionais.

Welcome:
- session_id efêmero gerado pelo servidor.
- user_id efêmero gerado pelo servidor.
- display_name.
- limits.
- ping_interval_ms.

Importante:
- session_id e user_id são efêmeros.
- O cliente não deve usar user_id como credencial.
- O servidor não deve confiar em user_id enviado pelo cliente.
- O Protobuf não deve conter nenhuma estrutura de persistência.

Além do .proto, criar docs/protobuf-contract.md explicando:
- Por que Protobuf não implica gRPC.
- Como GhostEnvelope é usado dentro de WebSocket binary frames.
- Como gerar código em Java, Go, Rust, Node, Python e mobile.
- Como evoluir o contrato sem quebrar clientes antigos.
- Por que não existem campos de IP/fingerprint no contrato.
```

---

# Prompt 4 — Criar arquitetura de referência separada do protocolo

```txt
Crie o documento de arquitetura de referência do Ghost Bunker.

Criar arquivo:
- docs/reference-architecture.md

Objetivo:
Descrever uma arquitetura possível para implementar uma aplicação usando Ghost Bunker Protocol v0.1, sem confundir arquitetura com o protocolo.

Premissas:
- O protocolo é independente de linguagem.
- A implementação de referência pode ser em Java, Go, Node, Rust ou outra linguagem.
- A comunicação pública é WSS.
- O payload é Protobuf binário.
- O conteúdo das mensagens é E2EE.
- O servidor não lê plaintext.
- O servidor roteia ciphertext.
- A v0.1 roda single-node e em memória.
- A aplicação de referência deve seguir o Privacy-Max Profile.

Camadas da arquitetura de referência:
1. WebSocket Endpoint
2. Protocol Decoder/Encoder
3. Connection Session Manager
4. Room Registry em memória
5. Message Router
6. Per-Connection Rate Limiter
7. Backpressure Manager
8. Heartbeat Manager
9. Protocol Validator
10. Sanitized Logger
11. Metrics agregadas não identificáveis

Não incluir como obrigatório na v0.1:
- Banco de dados.
- Redis.
- Kafka.
- RabbitMQ.
- Armazenamento de histórico.
- Registro persistente de IP.
- Bloqueio Tor/VPN dentro do core.
- Fingerprint.
- Analytics identificável.

Explicar:
- Como rodar single-node em memória.
- Como manter sessão associada à conexão WebSocket.
- Como rotear mensagem para membros da sala local.
- Como aplicar rate limit por conexão, não por identidade persistente.
- Como aplicar backpressure.
- Como lidar com cliente lento.
- Como lidar com payload inválido.
- Como não logar payload, IP, ciphertext ou headers.
- Como evoluir para multi-node sem mudar o protocolo.

Roadmap de arquitetura:
- v0.1: single-node, memória, sem histórico, sem persistência identificável.
- v0.2: multi-node experimental com pub/sub efêmero.
- v0.3: presença distribuída com TTL curto e sem identidade persistente.
- v0.4: histórico local opcional apenas no cliente.
- v1.0: modelo criptográfico mais avançado.

Não implementar código neste passo.
```

---

# Prompt 5 — Criar servidor de referência Java/Spring Boot

```txt
Crie o projeto servidor de referência do Ghost Bunker Protocol v0.1 usando Java 21 e Spring Boot 3.

Objetivo:
Implementar um servidor WebSocket que aceite conexões WS/WSS, receba mensagens Protobuf binárias, valide envelopes, gerencie sessões efêmeras em memória, gerencie salas em memória e roteie mensagens criptografadas sem ler o conteúdo.

Importante:
- Este é servidor de referência, não o protocolo em si.
- Não usar gRPC.
- Usar WebSocket.
- Usar Protobuf para serialização.
- Não trafegar texto puro de chat.
- Não descriptografar mensagens.
- O servidor não conhece plaintext.
- O servidor apenas roteia EncryptedMessage.
- Não criar banco de dados.
- Não adicionar Redis.
- Não adicionar Kafka.
- Não adicionar RabbitMQ.
- Não criar histórico.
- Não persistir IP.
- Não persistir payload.
- Não persistir ciphertext.
- Não criar fingerprint.
- Não criar conta/login.
- Não usar cookie persistente.
- Não usar sessão HTTP persistente.
- Não logar payload binário.
- Não logar ciphertext.
- Não logar headers.
- Não logar remote address.
- Logs técnicos devem ser sanitizados.

Stack:
- Java 21
- Spring Boot 3.x
- Spring WebSocket
- Protobuf Java
- Maven
- JUnit 5
- AssertJ
- Mockito quando necessário

Endpoint:
- /ghost-bunker

Estrutura sugerida:
- src/main/proto/ghost_bunker_v1.proto
- src/main/java/.../config/WebSocketConfig.java
- src/main/java/.../protocol/GhostEnvelopeDecoder.java
- src/main/java/.../protocol/GhostEnvelopeEncoder.java
- src/main/java/.../protocol/ProtocolLimits.java
- src/main/java/.../session/GhostSession.java
- src/main/java/.../session/GhostSessionState.java
- src/main/java/.../session/InMemoryGhostSessionRegistry.java
- src/main/java/.../room/Room.java
- src/main/java/.../room/InMemoryRoomRegistry.java
- src/main/java/.../routing/MessageRouter.java
- src/main/java/.../rate/PerConnectionRateLimiter.java
- src/main/java/.../backpressure/OutboundQueuePolicy.java
- src/main/java/.../heartbeat/HeartbeatService.java
- src/main/java/.../handler/GhostBunkerWebSocketHandler.java
- src/main/java/.../validation/ProtocolValidator.java
- src/main/java/.../error/ProtocolErrorMapper.java
- src/main/java/.../logging/SanitizedProtocolLogger.java

Fluxo:
1. Cliente conecta.
2. Servidor cria sessão efêmera em memória em estado AWAITING_HELLO.
3. Cliente deve enviar HELLO em até 5 segundos.
4. Servidor valida protocol, version e nickname.
5. Servidor responde WELCOME com session_id efêmero, user_id efêmero, limites e heartbeat.
6. Cliente envia JOIN_ROOM.
7. Servidor responde ROOM_JOINED.
8. Cliente envia SEND_ENCRYPTED_MESSAGE.
9. Servidor valida estado, sala, tamanho, nonce, key_id, enc_suite e rate limit por conexão.
10. Servidor responde MESSAGE_ACCEPTED ao remetente.
11. Servidor envia ENCRYPTED_MESSAGE aos demais participantes da sala.
12. Clientes podem enviar MESSAGE_RECEIVED_ACK.
13. PING/PONG mantém conexão viva.
14. Payload inválido gera ERROR.
15. Payload inválido repetido fecha conexão.
16. Ao fechar conexão, todos os identificadores efêmeros são descartados.

Estados:
- CONNECTED
- AWAITING_HELLO
- ESTABLISHED
- IN_ROOMS
- CLOSING
- CLOSED

Limites:
- Max envelope Protobuf: 64 KB
- Max ciphertext: 16 KB
- Max nickname: 32 caracteres ASCII visível
- Max room_id: 64 caracteres ASCII visível
- Max salas por conexão: 5
- Max mensagens por conexão: 20/minuto
- Max comandos por conexão: 60/minuto
- Max fila de saída por conexão: 100 mensagens
- Max bytes pendentes por conexão: 1 MB
- Handshake timeout: 5s
- Ping interval: 30s
- Pong timeout: 10s
- Idle timeout: 90s

Antiabuso privacy-max:
- Rate limit por conexão.
- Limite de payload.
- Limite de salas por conexão.
- Backpressure.
- Fechar sessão após 3 violações de protocolo em 60s.
- Não implementar ban persistente por IP.
- Não implementar fingerprint.
- Não implementar bloqueio Tor/VPN no core.

Validações:
- HELLO válido.
- HELLO ausente dentro do timeout.
- versão incompatível.
- nickname com emoji rejeitado.
- nickname não ASCII rejeitado.
- JOIN_ROOM antes de WELCOME rejeitado.
- SEND_ENCRYPTED_MESSAGE antes de JOIN_ROOM rejeitado.
- ciphertext acima do limite rejeitado.
- nonce ausente rejeitado.
- key_id ausente rejeitado.
- enc_suite ausente rejeitado.
- rate limit por conexão funcionando.
- payload Protobuf inválido fecha conexão.
- mensagem é roteada sem descriptografia.
- MESSAGE_RECEIVED_ACK aceito depois de ROOM_JOINED.
- PING/PONG funcionando.
- cliente lento é desconectado por backpressure.

Não criar frontend neste passo.
Manter v0.1 simples, robusta, efêmera e em memória.
```

---

# Prompt 6 — Criar cliente web de referência

```txt
Crie um cliente web de referência para o Ghost Bunker Protocol v0.1.

Objetivo:
Criar uma página web simples que conecta no servidor WebSocket, usa Protobuf binário, faz handshake, entra em sala, criptografa mensagens no cliente, envia ciphertext e descriptografa mensagens recebidas no cliente.

Importante:
- Este é cliente de referência.
- Não usar emoji.
- Bloquear emoji no input de nickname.
- Bloquear emoji no input de mensagem.
- Bloquear caracteres fora de ASCII visível para nickname.
- Bloquear caracteres fora de ASCII visível para room_id.
- O texto puro nunca deve ser enviado ao servidor.
- Antes de enviar, o cliente deve criptografar a mensagem.
- O servidor recebe apenas ciphertext.
- Ao receber mensagem, o cliente descriptografa localmente.
- Não usar login.
- Não usar conta.
- Não usar cookie persistente.
- Não exigir localStorage.
- Não salvar histórico por padrão.
- Não enviar fingerprint.
- Não enviar device_id.

Stack sugerida:
- TypeScript
- Vite
- protobufjs ou geração a partir do .proto
- Web Crypto API
- WebSocket nativo do browser

Criar:
- web-client/
- web-client/src/protocol/
- web-client/src/crypto/
- web-client/src/ui/
- web-client/src/main.ts

Funcionalidades:
1. Campo para URL WebSocket.
2. Campo para nickname.
3. Campo para room_id.
4. Campo para passphrase/chave da sala.
5. Botão conectar.
6. Após conectar, enviar HELLO.
7. Receber WELCOME.
8. Enviar JOIN_ROOM.
9. Exibir status da conexão.
10. Campo de mensagem.
11. Validar mensagem sem emoji antes de criptografar.
12. Validar mensagem <= 4 KB em UTF-8 antes de criptografar.
13. Derivar chave localmente com PBKDF2-HMAC-SHA256.
14. Criptografar com AES-256-GCM.
15. Gerar nonce/IV aleatório de 12 bytes por mensagem.
16. Enviar SEND_ENCRYPTED_MESSAGE com key_id, enc_suite, nonce e ciphertext.
17. Receber MESSAGE_ACCEPTED.
18. Receber ENCRYPTED_MESSAGE.
19. Descriptografar localmente.
20. Exibir mensagem na tela.
21. Enviar MESSAGE_RECEIVED_ACK.
22. Responder PING com PONG.
23. Mostrar erros de protocolo.

Criptografia v0.1:
- Passphrase da sala informada manualmente no cliente.
- Passphrase nunca enviada ao servidor.
- Salt pode ser derivado de room_id + constante de protocolo ou informado manualmente para referência.
- key_id pode ser hash curto não reversível da chave derivada ou identificador local da sala.
- Não enviar plaintext.
- Não enviar passphrase.
- Não enviar chave derivada.

Validações:
- Não permitir envio se não conectado.
- Não permitir envio se não entrou na sala.
- Não permitir emoji.
- Não permitir nickname inválido.
- Não permitir room_id inválido.
- Não permitir mensagem acima de 4 KB antes de criptografar.
- Não permitir ciphertext acima de 16 KB.
- Mostrar erro se falhar descriptografia.

Documentar limitações em web-client/README.md:
- v0.1 usa chave simétrica por sala.
- Não há troca de chaves avançada.
- Não há Double Ratchet.
- Não há forward secrecy forte.
- Quem sabe a passphrase da sala consegue descriptografar mensagens.
```

---

# Prompt 7 — Criar documentação E2EE v0.1 sem bola de neve

```txt
Crie docs/e2ee-v0.1.md explicando somente o modelo de criptografia da v0.1.

Objetivo:
Documentar a criptografia ponta-a-ponta simples e implementável da v0.1, sem incluir X25519, Double Ratchet ou PKI como requisito atual.

Premissas:
- O servidor não lê plaintext.
- O servidor não recebe passphrase.
- O servidor não recebe chave derivada.
- O servidor não descriptografa mensagens.
- O servidor apenas roteia ciphertext.
- A v0.1 usa chave simétrica por sala.

Modelo v0.1:
- Usuário informa passphrase da sala no cliente.
- Cliente deriva chave com PBKDF2-HMAC-SHA256.
- Cliente criptografa com AES-256-GCM.
- Cada mensagem usa nonce/IV aleatório de 12 bytes.
- O payload enviado contém enc_suite, key_id, nonce e ciphertext.
- Plaintext nunca atravessa o WebSocket.

Explicar:
- Diferença entre TLS e E2EE.
- TLS protege cliente-servidor.
- E2EE protege conteúdo contra o servidor.
- WSS não substitui E2EE.
- O que o servidor ainda consegue ver: horário, tamanho, sala, eventos e padrões de tráfego.
- Por que o servidor não consegue validar emoji dentro do ciphertext.
- Por que o cliente oficial deve bloquear emoji antes de criptografar.
- Risco de passphrase fraca.
- Risco de compartilhar passphrase fora de canal seguro.
- Limitação: sem forward secrecy forte.
- Limitação: sem rotação automática de chave.
- Limitação: se alguém sabe a passphrase, consegue ler mensagens da sala.

Criar uma seção "Fora da v0.1":
- X25519
- Ed25519
- Double Ratchet
- Multi-device
- Verificação fora de banda
- Rotação automática de chave
- Forward secrecy forte

Não implementar código neste passo.
```

---

# Prompt 8 — Criar testes de robustez do servidor

```txt
Crie uma suíte de testes de robustez para o servidor Ghost Bunker Protocol v0.1.

Objetivo:
Garantir que o servidor suporte entradas inválidas, mensagens quebradas, estados incorretos e abuso básico sem quebrar e sem persistir dados identificáveis.

Testes de handshake:
- conexão sem HELLO deve fechar após timeout
- HELLO válido deve receber WELCOME
- HELLO com protocol inválido deve receber ERROR
- HELLO com version incompatível deve receber UNSUPPORTED_VERSION
- HELLO com nickname contendo emoji deve ser rejeitado
- HELLO com nickname não ASCII deve ser rejeitado
- HELLO duplicado após WELCOME deve gerar PROTOCOL_VIOLATION

Testes de payload:
- frame textual deve ser rejeitado
- frame binário vazio deve ser rejeitado
- bytes aleatórios não Protobuf devem fechar conexão
- Protobuf válido mas sem payload esperado deve gerar BAD_ENVELOPE
- payload maior que 64 KB deve fechar conexão
- ciphertext maior que 16 KB deve gerar CIPHERTEXT_TOO_LARGE
- nonce ausente deve gerar BAD_METADATA
- nonce inválido deve gerar BAD_METADATA
- key_id ausente deve gerar BAD_METADATA
- enc_suite ausente deve gerar BAD_METADATA

Testes de estado:
- SEND_ENCRYPTED_MESSAGE antes de HELLO
- SEND_ENCRYPTED_MESSAGE antes de JOIN_ROOM
- MESSAGE_RECEIVED_ACK antes de JOIN_ROOM
- JOIN_ROOM antes de WELCOME
- LEAVE_ROOM de sala não participada
- DISCONNECT em qualquer estado permitido

Testes de rate limit privacy-max:
- mais de 20 mensagens por minuto na mesma conexão
- mais de 60 comandos por minuto na mesma conexão
- payload inválido repetido fecha conexão após 3 tentativas em 60s
- nenhum teste deve depender de persistência de IP

Testes de roteamento:
- mensagem enviada para sala correta
- mensagem não enviada para sala errada
- remetente recebe MESSAGE_ACCEPTED
- participantes recebem ENCRYPTED_MESSAGE
- servidor não tenta descriptografar ciphertext
- servidor preserva bytes do ciphertext
- servidor não ecoa plaintext porque plaintext nunca existe no servidor

Testes de heartbeat:
- PING recebe PONG
- ausência de PONG fecha conexão
- idle timeout fecha conexão

Testes de backpressure:
- cliente lento excedendo fila de saída é desconectado com CLIENT_TOO_SLOW
- fila pendente não cresce indefinidamente

Testes de privacidade:
- logs não contêm payload
- logs não contêm ciphertext
- logs não contêm headers
- logs não contêm remote address
- logs não contêm session_id completo se houver log técnico
- logs usam apenas mensagens sanitizadas

Use JUnit 5.
Use testes claros e pequenos.
Evite dependência de banco de dados.
Não adicionar Redis/Kafka.
```

---

# Prompt 9 — Preparar evolução multi-instância sem quebrar privacy-max

```txt
Prepare a evolução do Ghost Bunker Server para arquitetura multi-instância, mas sem implementar Redis/Kafka ainda.

Objetivo:
Refatorar a arquitetura para permitir múltiplas instâncias futuramente, mantendo o protocolo independente de linguagem e o perfil privacy-max.

Criar interfaces:
- SessionRegistry
- RoomRegistry
- PresenceRegistry
- MessageRouter
- MessageBus
- RateLimitStore
- BackpressurePolicy

Implementações v0.1:
- InMemorySessionRegistry
- InMemoryRoomRegistry
- InMemoryPresenceRegistry
- LocalMessageRouter
- NoopMessageBus
- InMemoryPerConnectionRateLimitStore
- DefaultBackpressurePolicy

Regras:
- O handler WebSocket não deve depender diretamente de implementações em memória.
- O handler deve depender de interfaces.
- O roteamento de mensagem deve passar por MessageRouter.
- O MessageRouter deve conseguir entregar localmente agora e futuramente publicar em bus.
- Não adicionar Redis ainda.
- Não adicionar Kafka ainda.
- Não adicionar banco.
- Não persistir IP.
- Não persistir ciphertext.
- Não persistir payload.
- Não criar identity store.
- Não criar fingerprint store.

Criar docs/scaling-plan.md explicando:
- Como evoluir para multi-node sem alterar o Protobuf.
- Como manter sessões WebSocket locais.
- Como usar pub/sub efêmero no futuro.
- Como rotear mensagem para usuário conectado em outra instância.
- Como usar TTL curto para presença.
- Como evitar persistência identificável.
- Como lidar com deploy rolling.
- Como manter compatibilidade do Protobuf.
- Quais decisões são da aplicação e não do protocolo.

Opções futuras:
- NATS para pub/sub efêmero.
- Redis Pub/Sub para fanout simples.
- Kafka apenas se o produto decidir persistir eventos, o que não faz parte do perfil privacy-max.
```

---

# Prompt 10 — Criar README geral do projeto

```txt
Crie o README principal do projeto Ghost Bunker.

O README deve explicar:

1. O que é o Ghost Bunker Protocol.
2. O que ele não é.
3. Diferença entre protocolo, perfil de privacidade e aplicação.
4. Objetivo da v0.1.
5. Transporte usado.
6. Por que WebSocket.
7. Por que Protobuf.
8. Por que não é gRPC.
9. Como funciona o GhostEnvelope.
10. Como funciona a criptografia ponta-a-ponta v0.1.
11. Diferença entre TLS e E2EE.
12. Por que o servidor não lê mensagens.
13. Como funciona identidade anônima efêmera.
14. Como funciona handshake.
15. Como funciona sala.
16. Como funciona envio de mensagem criptografada.
17. Como funciona confirmação de recebimento.
18. Como funciona heartbeat.
19. Como funciona antiabuso sem identidade persistente.
20. Por que emoji é bloqueado no cliente oficial.
21. Limites do protocolo.
22. Limitações da v0.1.
23. Como rodar servidor local.
24. Como rodar cliente web local.
25. Como gerar código Protobuf.
26. Como evoluir para multi-instância.
27. Roadmap.

Regras:
- Linguagem objetiva.
- Sem prometer segurança absoluta.
- Deixar claro que v0.1 é uma versão inicial.
- Deixar claro que IP existe tecnicamente durante uma conexão TCP/WebSocket, mas o perfil privacy-max não deve persistir IP.
- Deixar claro que o protocolo não possui campos de IP/fingerprint.
- Deixar claro que bloqueio Tor/VPN/proxy não faz parte do core do protocolo.
- Deixar claro que E2EE reduz visibilidade do servidor, mas metadata operacional ainda existe durante a conexão.
- Deixar claro que o servidor não armazena histórico na implementação de referência.
```

---

# Prompt 11 — Auditoria privacy-max da implementação

```txt
Faça uma auditoria privacy-max da implementação atual do Ghost Bunker Server.

Objetivo:
Verificar se o servidor de referência respeita o perfil de privacidade máxima.

Verificar:
- Se existe qualquer banco de dados.
- Se existe qualquer escrita em arquivo contendo IP, payload, ciphertext, headers ou identificadores.
- Se logs contêm remote address.
- Se logs contêm headers.
- Se logs contêm payload Protobuf.
- Se logs contêm ciphertext.
- Se logs contêm session_id completo.
- Se logs contêm user_id completo.
- Se existe cookie persistente.
- Se existe sessão HTTP persistente.
- Se existe fingerprint.
- Se existe device_id.
- Se existe analytics.
- Se existe access log ativo.
- Se exceptions podem imprimir payload ou bytes recebidos.
- Se WebSocket handler registra dados sensíveis em erro.
- Se rate limit depende de IP persistente.
- Se antiabuso depende de ban persistente.

Resultado esperado:
- Criar docs/privacy-audit.md.
- Listar achados por severidade: CRITICAL, HIGH, MEDIUM, LOW.
- Propor correções mínimas.
- Não implementar correções sem pedir novo prompt.
```

---

# Prompt 12 — Ajustar logs para privacy-max

```txt
Ajuste a implementação de logs do Ghost Bunker Server para cumprir o Privacy-Max Profile v0.1.

Regras:
- Não logar IP.
- Não logar remote address.
- Não logar headers.
- Não logar payload.
- Não logar ciphertext.
- Não logar bytes recebidos.
- Não logar session_id completo.
- Não logar user_id completo.
- Não logar nickname em erro de validação.
- Não imprimir stacktrace com payload.
- Não usar request dump.

Permitido:
- Logar tipo de evento genérico.
- Logar código de erro.
- Logar contadores agregados.
- Logar session_id mascarado, se necessário, com no máximo os 4 primeiros caracteres e sem persistência obrigatória.
- Logar mensagem técnica sanitizada.

Criar ou ajustar:
- SanitizedProtocolLogger
- LogSanitizer
- ProtocolExceptionHandler

Adicionar testes garantindo que:
- payload não aparece no log
- ciphertext não aparece no log
- remote address não aparece no log
- headers não aparecem no log
- session_id completo não aparece no log
```

---

# Observação final

Use primeiro os prompts 1, 2 e 3.

Depois implemente o servidor com o prompt 5.

O prompt 4 é documentação de arquitetura.

O prompt 6 cria o cliente web.

Os prompts 11 e 12 são importantes depois que existir código, porque garantem que a implementação não desvie do perfil privacy-max.

