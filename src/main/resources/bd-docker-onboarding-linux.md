# PostgreSQL en Docker — Onboarding Linux

## Levantar PostgreSQL

```bash
docker run -d --name postgres-pos \
  -e POSTGRES_DB=controlneg_rmx_db \
  -e POSTGRES_USER=romax-admin \
  -e POSTGRES_PASSWORD=f4ast3rv3rs10n* \
  -p 5432:5432 \
  -v postgres_data:/var/lib/postgresql/data \
  postgres:17
```

### Parámetros

| Variable | Valor |
|---|---|
| `POSTGRES_DB` | `controlneg_rmx_db` |
| `POSTGRES_USER` | `romax-admin` |
| `POSTGRES_PASSWORD` | `f4ast3rv3rs10n*` |
| Puerto host | `5432` |
| Puerto contenedor | `5432` |
| Volumen | `postgres_data` → `/var/lib/postgresql/data` |
| Imagen | `postgres:17` |

## Iniciar / Detener

```bash
docker start postgres-pos
docker stop postgres-pos
```

## Persistencia

El volumen con nombre `postgres_data` conserva los datos aunque el contenedor se elimine. Para recrear el contenedor sin perder datos:

```bash
docker rm -f postgres-pos
docker run -d --name postgres-pos \
  -e POSTGRES_DB=controlneg_rmx_db \
  -e POSTGRES_USER=romax-admin \
  -e POSTGRES_PASSWORD=f4ast3rv3rs10n* \
  -p 5432:5432 \
  -v postgres_data:/var/lib/postgresql/data \
  postgres:17
```

## Restaurar desde backup

```bash
docker exec -i postgres-pos pg_restore \
  -U romax-admin -d controlneg_rmx_db \
  < /ruta/al/dump-archivo.sql
```

## Verificar

```bash
# Conexión
docker exec -i postgres-pos psql -U romax-admin -d controlneg_rmx_db -c "\dt"

# Estado
docker exec postgres-pos pg_isready
```

## Notas

- Si el contenedor se detiene o el sistema se reinicia, basta con `docker start postgres-pos`.
- El backup del dump original está en `/home/carlosr/Documentos/dev/doc/backups/dump-controlneg_rmx_db-202605161945.sql`.
- Los logs del contenedor se ven con `docker logs postgres-pos`.
- Para acceder a la consola interactiva de PostgreSQL: `docker exec -it postgres-pos psql -U romax-admin -d controlneg_rmx_db`.
