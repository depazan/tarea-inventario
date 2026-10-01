# DECISIONS

Este documento resume los supuestos que tomé para resolver el challenge, las decisiones técnicas de la implementación actual, lo que dejé fuera del alcance y los cambios que haría antes de llevar esta solución a producción.

## 1. Supuestos tomados

### `orderId` como clave de idempotencia

Tomé `orderId` como identificador único de la operación de reserva.

Si llega nuevamente:

```text
orderId = ORDER-1
sku = SKU-1
quantity = 2
```

y la reserva sigue activa, se considera un retry de la misma operación y se retorna la misma reserva sin volver a descontar inventario.

Si el mismo `orderId` llega con otro SKU o con otra cantidad, se rechaza porque ya representaría una operación diferente.

Si una reserva falla antes de crearse, por ejemplo por stock insuficiente, el `orderId` no queda consumido y puede volver a utilizarse en otro intento.

Una reserva ya confirmada o expirada no puede volver a crearse usando el mismo `orderId`.

---

### Expiración

Una reserva se considera expirada cuando:

```text
now >= expiresAt
```

Por lo tanto, exactamente en `expiresAt` ya no es válida.

No agregué un scheduler para expirar reservas. La expiración es lazy y se evalúa antes de operaciones que necesitan trabajar con el estado real del SKU, como:

- `reserve`
- `confirm`
- `available`
- `addStock`

Cuando una reserva expira, las unidades reservadas vuelven a estar disponibles.

---

### Stock disponible

Internamente mantengo:

```text
stock
reservedUnits
```

y calculo:

```text
available = stock - reservedUnits
```

Al reservar, aumenta `reservedUnits`.

Al confirmar una reserva:

```text
reservedUnits -= quantity
stock -= quantity
```

De esta forma las unidades reservadas pasan a ser unidades vendidas y la disponibilidad no cambia al momento de confirmar.

---

### Low stock

Tomé como condición:

```text
available <= 5
```

La alerta se envía una sola vez mientras el producto continúe en low stock.

Solo `addStock` puede rearmar la alerta, y únicamente cuando la disponibilidad vuelve a ser mayor que 5.

Si una reserva expira y libera unidades, eso no lo considero una reposición real de inventario, por lo tanto no rearma la alerta.

---

## 2. Decisiones técnicas

### Separación entre aplicación, dominio y almacenamiento

La implementación quedó separada principalmente así:

```text
Inventory
    |
    v
DefaultInventoryService
    |
    +--> InventoryRepository
    |
    +--> ProductState
```

`Inventory` queda como punto de composición y construye las dependencias actuales.

`DefaultInventoryService` implementa el contrato público y coordina los casos de uso.

`ProductState` contiene el estado y las reglas de negocio de un SKU.

`InventoryRepository` abstrae el almacenamiento.

Actualmente la implementación concreta es:

```text
InMemoryInventoryRepository
```

No agregué Spring ni un framework de DI porque para una librería pequeña no era necesario. La inyección se hace por constructor.

---

### `ProductState` como dueño del estado de un SKU

Cada SKU tiene su propio `ProductState`.

Ahí se mantiene:

```text
stock
reservedUnits
reservationsByOrderId
lowStockAlertActive
```

También se manejan:

- reserva
- confirmación
- expiración
- disponibilidad
- idempotencia dentro del SKU
- low stock

La idea fue evitar repartir estas reglas entre el service y el repository.

---

### Concurrencia por SKU

Cada `ProductState` tiene su propio:

```java
ReentrantLock
```

Esto evita overselling cuando varios threads intentan operar sobre el mismo producto.

Ejemplo:

```text
available = 1

Thread A -> reserve 1
Thread B -> reserve 1
```

Solo uno entra a modificar el estado primero. El otro evalúa el stock después y falla con `InsufficientStockException`.

No utilicé un lock global porque bloquearía operaciones de productos diferentes sin necesidad.

Por ejemplo:

```text
Thread A -> SKU-1
Thread B -> SKU-2
```

pueden ejecutarse en paralelo.

---

### Concurrencia por `orderId`

El repository mantiene un índice:

```text
orderId -> sku
```

usando `ConcurrentHashMap`.

Para una reserva se utiliza:

```java
orderSkuIndex.compute(...)
```

Esto serializa las operaciones concurrentes sobre el mismo `orderId`.

La reserva se ejecuta primero y el `orderId` se asocia al SKU solamente si la operación termina correctamente.

Esto permite que un intento fallido no deje un `orderId` bloqueado.

La idempotencia final se completa entre el repository y `ProductState`: el repository coordina el mismo `orderId` y `ProductState` detecta si ya existe una reserva activa para esa orden.

---

### Políticas por categoría

Las reglas de categoría se movieron a:

```text
CategoryPolicy
CategoryPolicies
```

Actualmente:

| Categoría | TTL | Máximo por orden |
|---|---:|---:|
| STANDARD | 15 minutos | Sin límite |
| PRE_ORDER | 24 horas | Sin límite |
| FLASH_SALE | 5 minutos | 2 |

Esto evita tener reglas de categoría repetidas dentro del service o de `ProductState`.

Existe una limitación: `ProductCategory` pertenece al contrato público y es un enum. Si en el futuro aparece una categoría nueva, habría que agregarla al enum y también configurar su policy.

---

### Uso de `Clock`

Toda la lógica de tiempo parte del `Clock` recibido por:

```java
Inventory.create(Clock, StockAlertListener)
```

Evité usar directamente:

```java
Instant.now()
```

Esto permite probar expiraciones sin `Thread.sleep()` y tener tests determinísticos.

---

### Listener fuera del lock

`ProductState` solo determina si corresponde generar una alerta.

La llamada real a:

```text
StockAlertListener
```

se realiza desde el service después de terminar la modificación protegida por el lock.

No quise ejecutar una operación externa mientras el SKU continúa bloqueado.

---

## 3. Qué dejé fuera

Para este challenge mantuve la implementación en memoria y no agregué componentes que no eran necesarios para resolver el problema actual.

Quedaron fuera:

- base de datos
- JPA / Hibernate
- Spring
- Redis
- Kafka / RabbitMQ
- scheduler de expiraciones
- locks distribuidos
- Docker
- transactional outbox
- retry de notificaciones
- observabilidad y métricas
- persistencia después de reiniciar la aplicación

La idea fue mantener una solución simple y comprobable sin intentar construir desde ahora una arquitectura distribuida que el challenge todavía no necesita.

---

## 4. Qué cambiaría antes de producción

La implementación actual resuelve concurrencia dentro de una sola JVM.

Esto es importante porque:

```java
ReentrantLock
```

no protege dos instancias diferentes de la aplicación.

Si tuviera:

```text
Instance A
Instance B
```

cada una tendría sus propios locks y su propia memoria.

Antes de producción movería el estado a un almacenamiento compartido y la garantía contra overselling tendría que estar también en ese almacenamiento.

### Persistencia y transacciones

Usaría una base de datos compartida y manejaría la reserva dentro de una transacción.

Dependiendo de la carga y del motor podría utilizar:

- optimistic locking con versión
- row locking
- update condicional

Por ejemplo, conceptualmente:

```sql
UPDATE inventory
SET available = available - :quantity
WHERE sku = :sku
  AND available >= :quantity;
```

y verificar que realmente se haya actualizado una fila.

El objetivo es que la propia operación en DB garantice que dos instancias no puedan vender las mismas unidades.

---

### Idempotencia en DB

Mantendría `orderId` como clave de idempotencia pero lo reforzaría mediante una restricción única en persistencia.

Por ejemplo:

```text
UNIQUE(order_id)
```

La validación ya no dependería solamente del `ConcurrentHashMap` de una JVM.

---

### Expiraciones

La expiración lazy es suficiente para el challenge, pero en producción agregaría un proceso de limpieza.

Por ejemplo:

```text
scheduled worker
        |
        v
buscar ACTIVE con expires_at <= now
        |
        v
marcar EXPIRED / liberar reserva
```

La validación de expiración también debería seguir existiendo dentro de la operación de negocio para no depender exclusivamente del worker.

---

### Alertas

Actualmente el listener se ejecuta de forma síncrona y best-effort.

Si el inventario cambia correctamente pero falla el envío del email, el cambio de inventario ya quedó aplicado.

Si la notificación fuera importante para negocio, usaría un patrón como:

```text
Inventory transaction
        |
        +--> inventory update
        |
        +--> outbox event
                 |
                 v
            message broker
                 |
                 v
          notification service
```

Esto permitiría reintentar el envío sin tener que revertir la operación principal de inventario.

---

### Repository

`InventoryRepository` desacopla actualmente el service de `InMemoryInventoryRepository`, pero no asumiría que una implementación de DB sería solamente cambiar una clase por otra.

El repository actual trabaja con `ProductState` mutable y ejecuta una `reservationAction` dentro de `compute`.

Con DB revisaría ese contrato para que los límites transaccionales queden definidos alrededor de las operaciones reales de persistencia.

La lógica de negocio seguiría siendo la misma conceptualmente, pero la estrategia de locking y atomicidad cambiaría.

---

## 5. Tests

Además de los tests originales agregué pruebas para las reglas principales y para los puntos de concurrencia.

Actualmente:

```text
Tests run: 52
Failures: 0
Errors: 0
Skipped: 0
```

Se cubren entre otros:

- stock y disponibilidad
- reservas
- confirmación
- expiración
- límite de FLASH_SALE
- TTL por categoría
- idempotencia
- retry de una reserva
- low stock
- rearmado de alertas
- reserva fallida sin consumir `orderId`
- concurrencia por última unidad
- dos retries concurrentes del mismo `orderId`

Para las pruebas de tiempo utilicé `Clock.fixed` / `Instant` y para concurrencia `ExecutorService` y `CountDownLatch`.

No fue necesario modificar código de producción para hacer pasar los tests nuevos.

---

## 6. Resumen

Para el alcance actual prioricé:

```text
correctitud
simplicidad
idempotencia
thread safety
testabilidad
separación de responsabilidades
```

La solución actual está pensada para ejecutarse in-memory dentro de una JVM.

No intenté esconder esa limitación detrás de una abstracción.

`InventoryRepository` deja separado el almacenamiento, pero para llevarlo a múltiples instancias cambiaría la estrategia de concurrencia hacia transacciones y garantías de base de datos, además de agregar persistencia de reservas, manejo activo de expiraciones y entrega durable de alertas.
