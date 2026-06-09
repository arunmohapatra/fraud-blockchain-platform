# Java Blockchain Service

A simple, in-memory blockchain implementation in Java with Spring Boot. Designed for the Fraud Detection System hackathon project.

## Features

- ✅ Complete blockchain implementation with mining
- ✅ SHA-256 hashing with proof-of-work
- ✅ Transaction management with pending pool
- ✅ Account management with balances
- ✅ Block validation and chain integrity
- ✅ REST API for all operations
- ✅ Swagger UI documentation
- ✅ **No external dependencies** (no Ganache, no Node.js)

## Quick Start

### Run in IntelliJ

1. Open project in IntelliJ
2. Run `BlockchainServiceApplication.java`
3. Open: http://localhost:8545/swagger-ui.html

### Run with Maven

```bash
cd blockchain-service
mvn clean install
mvn spring-boot:run
```

## API Endpoints

### Blockchain Info
- `GET /api/blockchain/info` - Get blockchain statistics
- `GET /api/blockchain/validate` - Validate chain integrity

### Blocks
- `GET /api/blockchain/blocks` - Get all blocks
- `GET /api/blockchain/blocks/latest` - Get latest block
- `GET /api/blockchain/blocks/height` - Get block height
- `POST /api/blockchain/mine` - Mine a new block

### Transactions
- `POST /api/blockchain/transactions` - Submit transaction
- `GET /api/blockchain/transactions` - Get all transactions
- `GET /api/blockchain/transactions/{hash}` - Get transaction by hash
- `GET /api/blockchain/transactions/pending` - Get pending transactions

### Accounts
- `GET /api/blockchain/accounts` - Get all accounts
- `GET /api/blockchain/accounts/{address}` - Get account details
- `GET /api/blockchain/accounts/{address}/balance` - Get balance

## How It Works

### 1. Genesis Block

On startup, a genesis block is automatically created:

```java
Block genesis = Block.builder()
    .index(0L)
    .timestamp(Instant.now().toEpochMilli())
    .previousHash("0")
    .transactions(new ArrayList<>())
    .build();
```

### 2. Submit Transaction

When you submit a transaction:

```json
POST /api/blockchain/transactions
{
  "from": "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
  "to": "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
  "amount": 500,
  "riskScore": 45,
  "riskLevel": "MEDIUM"
}
```

The transaction is added to the pending pool.

### 3. Mining

When there are pending transactions, a new block is mined:

```java
Block newBlock = Block.builder()
    .index(lastBlock.getIndex() + 1)
    .previousHash(lastBlock.getHash())
    .transactions(pendingTransactions)
    .difficulty(4)
    .build();

newBlock.mineBlock(difficulty); // Proof-of-work
blockchain.add(newBlock);
```

Mining uses SHA-256 with proof-of-work (4 leading zeros by default).

### 4. Block Structure

```java
{
  "index": 1,
  "timestamp": 1234567890,
  "previousHash": "00001a2b3c...",
  "hash": "0000fa7e8d...",
  "nonce": 12345,
  "difficulty": 4,
  "transactions": [...]
}
```

## Default Accounts

Six accounts are created on startup with 100 ETH each:

1. `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266`
2. `0x70997970C51812dc3A010C7d01b50e0d17dc79C8`
3. `0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC`
4. `0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199`
5. `0xdD2FD4581271e230360230F9337D5c0430Bf44C0`
6. `0x90F79bf6EB2c4f870365E785982E1f101E93b906`

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
server:
  port: 8545

blockchain:
  mining:
    difficulty: 4  # Number of leading zeros
    reward: 50
```

## Example Usage

### Submit Transaction

```bash
curl -X POST http://localhost:8545/api/blockchain/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "from": "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "to": "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
    "amount": 10,
    "riskScore": 25,
    "riskLevel": "LOW"
  }'
```

### Get Blockchain Info

```bash
curl http://localhost:8545/api/blockchain/info
```

Response:
```json
{
  "blockHeight": 5,
  "totalBlocks": 6,
  "totalTransactions": 12,
  "pendingTransactions": 0,
  "difficulty": 4,
  "isValid": true
}
```

### Get All Blocks

```bash
curl http://localhost:8545/api/blockchain/blocks
```

## Used By

This blockchain service is used by the **Fraud Detection Application** running on port 8080.

The fraud detection app calls this service via REST to:
- Submit payment transactions
- Store immutable audit trail
- Verify blockchain status

## Benefits

✅ **Pure Java** - No JavaScript/Node.js required
✅ **In-Memory** - Fast for demos and testing
✅ **Simple** - Easy to understand and modify
✅ **Complete** - Real blockchain with mining
✅ **RESTful** - Standard HTTP APIs
✅ **Documented** - Swagger UI included

## For Production

To make this production-ready:

1. **Add Persistence** - Store blocks in database
2. **Add Security** - Authentication, rate limiting
3. **Optimize Mining** - Use thread pools
4. **Add Consensus** - Implement consensus algorithm
5. **Scale** - Distributed blockchain nodes

## Architecture

```
┌─────────────────────────────────┐
│   REST Controller               │
│   - BlockchainController        │
└──────────────┬──────────────────┘
               │
┌──────────────▼──────────────────┐
│   Service Layer                 │
│   - BlockchainService           │
│   - Mining logic                │
│   - Validation                  │
└──────────────┬──────────────────┘
               │
┌──────────────▼──────────────────┐
│   Models                        │
│   - Block                       │
│   - Transaction                 │
│   - Account                     │
└─────────────────────────────────┘
```

## Tech Stack

- **Spring Boot 3.2** - Framework
- **Java 17** - Language
- **SHA-256** - Hashing algorithm
- **Proof-of-Work** - Mining algorithm
- **Swagger** - API documentation
- **Maven** - Build tool

---

**Simple, self-contained blockchain in pure Java!** 🚀
