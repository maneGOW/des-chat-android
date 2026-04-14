# DesChat

DesChat es una app Android de mensajería descentralizada que permite enviar mensajes sin internet mediante una red mesh BLE entre dispositivos cercanos.

Está pensada como un proyecto de portafolio para demostrar arquitectura modular, comunicación local entre nodos, persistencia de mensajes y diseño enfocado en escenarios donde no existe conectividad tradicional.

## Características

- Chat offline sin depender de internet.
- Comunicación entre dispositivos cercanos vía BLE.
- Enfoque en red mesh con reenvío de mensajes entre nodos.
- Persistencia local de conversaciones.
- Arquitectura modular pensada para crecer a multiplataforma en el futuro.
- Proyecto orientado a portafolio técnico.

## Objetivo

El objetivo de DesChat es explorar una solución de mensajería local y descentralizada para casos como:

- Emergencias naturales.
- Eventos masivos.
- Zonas sin cobertura.
- Escenarios donde se limita o bloquea el acceso a internet.

## Stack

- Kotlin
- Android
- Jetpack Compose
- Coroutines
- Flow
- Modularización por capas
- BLE / Bluetooth Low Energy
- Persistencia local

## Arquitectura

El proyecto está dividido en módulos para mantener una separación clara de responsabilidades.

Algunos módulos principales son:

- `core-model`: modelos base del dominio.
- `domain`: casos de uso e ինտերfaces.
- `mesh`: lógica de red y transmisión.
- `feature-*`: pantallas y funcionalidades de la app.

## Estado actual

El proyecto está en desarrollo inicial.

Actualmente se está trabajando en:

- Base modular del proyecto.
- Modelos compartidos.
- Estructura de dominio.
- Preparación para la capa de comunicación BLE.

## Próximos pasos

- Crear los casos de uso de dominio.
- Conectar la interfaz con datos simulados.
- Implementar descubrimiento de peers.
- Integrar envío y reenvío de mensajes.
- Persistencia de conversaciones.
- Mejorar la experiencia visual de la app.

## Capturas

Próximamente.

## Instalación

1. Clona el repositorio.
2. Abre el proyecto en Android Studio.
3. Ejecuta la app en un dispositivo físico con Bluetooth activado.

## Requisitos

- Android Studio reciente.
- Android 8.0 o superior.
- Bluetooth activado.
- Dispositivo físico recomendado para pruebas BLE.

## Nota

DesChat es un proyecto personal de exploración técnica y portafolio. La implementación puede evolucionar conforme se prueben distintos enfoques de red mesh y comunicación local.

---

Made with ❤️ by MrNullPointer
