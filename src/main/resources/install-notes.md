
# Instrucciones de Instalación y Requisitos

## Requisitos Previos

Para ejecutar la aplicación en un equipo Windows, necesitarás lo siguiente:

1.  **Java Runtime Environment (JRE) 11 o superior:** La aplicación está compilada con Java 11. Puedes descargar una versión compatible desde [Adoptium](https://adoptium.net/temurin/releases/?version=11).

## Generación del Ejecutable

Para crear un archivo ejecutable de la aplicación, sigue estos pasos:

1.  Abre una terminal en la raíz del proyecto.
2.  Ejecuta el siguiente comando de Maven:

    ```bash
    mvn clean package
    ```

3.  Este comando generará un archivo JAR auto-contenido en la carpeta `target`. El archivo se llamará `launcher-1.0-SNAPSHOT.jar`.

## Instalación y Ejecución en el Equipo de Destino

1.  Copia el archivo `launcher-1.0-SNAPSHOT.jar` generado en el paso anterior al equipo donde quieres instalar la aplicación.
2.  Para iniciar la aplicación, abre una terminal en la ubicación del archivo JAR y ejecuta el siguiente comando:

    ```bash
    java -jar launcher-1.0-SNAPSHOT.jar
    ```

Con estos pasos, tendrás un único archivo JAR que contiene tu aplicación y todas sus dependencias, listo para ser distribuido y ejecutado en cualquier máquina con Java 11 o superior.
