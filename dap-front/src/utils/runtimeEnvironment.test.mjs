import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractEnvVarsFromConfig as read, mergeEnvVarsIntoConfig as write } from './runtimeEnvironment.ts'

test('legacy DB credentials and port bindings survive migration and repeated editing', () => {
  const old = JSON.stringify({ database: { host: 'dbhost', password: 'p${x}!', schema: 'existing_schema' },
    ports: { services: [{ key: 'api', containerPort: 8200 }] },
    envVars: [{ name: 'SERVER_PORT', value: '{ports.api}' },
      { name: 'DB_PASS', value: '{database.password}', enabled: true }] })
  const rows = read(old), saved = write(old, rows)
  assert.equal(JSON.parse(saved).database, undefined)
  assert.equal(JSON.parse(saved).schemaEnv, 'DB_SCHEMA')
  assert.equal(JSON.parse(saved).ports.services, undefined)
  assert.equal(rows.find(r => r.name === 'SERVER_PORT').serviceKey, 'api')
  assert.equal(rows.find(r => r.name === 'DB_PASSWORD').value, 'p${x}!')
  assert.equal(rows.find(r => r.name === 'DB_SCHEMA').value, 'existing_schema')
  assert.deepEqual(read(saved), rows)
})

test('rejects invalid service port and duplicate service keys', () => {
  assert.throws(() => write('{}', [{ name: 'PORT', value: '70000', serviceKey: 'api' }]))
  assert.throws(() => write('{}', [{ name: 'A', value: '8200', serviceKey: 'api' }, { name: 'B', value: '8201', serviceKey: 'api' }]))
})
