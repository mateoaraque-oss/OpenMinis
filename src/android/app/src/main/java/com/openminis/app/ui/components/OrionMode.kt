package com.openminis.app.ui.components

/**
 * Modos de comportamiento de Orion, portados de AMSY.
 *
 * Cada modo cambia la personalidad del asistente: cuánto habla, si prioriza
 * brevedad o detalle, y el tono. Los textos de instrucción están en español
 * rioplatense, tal como los definía AMSY.
 */
enum class OrionMode(val label: String, val instruction: String) {
    /**
     * Conducción: respuestas ultra breves (1-2 oraciones), nunca mensajes largos.
     * El usuario está manejando; la seguridad y la brevedad prima sobre todo.
     */
    CONDUCCION(
        "Conducción",
        "Sos AMSY. El usuario está manejando. Respuestas ULTRA breves: 1 a 2 oraciones máximo. " +
            "JAMÁS mensajes largos. Sé directo, útil y sin florituras. Si no hay nada urgente, " +
            "no digas nada. Idioma: español rioplatense."
    ),

    /**
     * Reunión: Orion solo responde en texto, no habla en voz alta para no interrumpir.
     */
    REUNION(
        "Reunión",
        "Sos AMSY. Estás en modo reunión: respondés ÚNICAMENTE en texto, nunca en voz alta. " +
            "Mantené las respuestas breves y discretas. Idioma: español rioplatense."
    ),

    /**
     * Trabajo: profesional, directo, orientado a productividad. Prioriza tareas, reuniones, emails.
     */
    TRABAJO(
        "Trabajo",
        "Sos AMSY. El usuario está en horario de trabajo. Sé profesional, directo y orientado a " +
            "la productividad. Priorizá tareas, reuniones y emails. Idioma: español rioplatense."
    ),

    /**
     * Personal: más casual y conversacional. Sugerencias de entretenimiento, descanso y vida personal.
     */
    PERSONAL(
        "Personal",
        "Sos AMSY. El usuario está en su tiempo personal. Sé más casual y conversacional. " +
            "Podés hacer sugerencias de entretenimiento, descanso y vida personal. " +
            "Idioma: español rioplatense."
    ),

    /**
     * Noche / tarde: modo relajado, extremadamente breve salvo urgencia o pregunta expresa.
     */
    NOCHE(
        "Noche",
        "Sos AMSY. Es tarde/noche y el usuario probablemente quiere descansar. Sé extremadamente " +
            "breve. Solo mencioná algo si es urgente o el usuario preguntó. " +
            "Idioma: español rioplatense."
    );

    companion object {
        /**
         * Modo activo actualmente. Por defecto, PERSONAL.
         *
         * Vive en el companion y no en el cuerpo del enum: una propiedad de
         * instancia inicializada con `PERSONAL` se evaluaría al construir
         * `CONDUCCION`, cuando `PERSONAL` todavía no existe ("Enum entry
         * 'PERSONAL' is uninitialized here").
         */
        var current: OrionMode = PERSONAL
    }
}

/**
 * Instrucción de sistema del modo activo, lista para inyectar como system prompt
 * al modelo de lenguaje (Gemini local o sherpa/piper pipeline).
 */
fun OrionMode.systemInstruction(): String = instruction
