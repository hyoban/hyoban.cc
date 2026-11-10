package cc.hyoban.moments.data.spec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Serializes a record through its generated serializer, but keeps every field the
 * record class doesn't declare in [extraOf] and writes it back out, so a record from a
 * newer spec round-trips without losing anything.
 */
@OptIn(ExperimentalSerializationApi::class)
abstract class PassthroughSerializer<T : Any>(
    private val generated: KSerializer<T>,
    private val withExtra: (T, JsonObject) -> T,
    private val extraOf: (T) -> JsonObject,
) : KSerializer<T> {
    private val known = generated.descriptor.elementNames.toSet()

    override val descriptor: SerialDescriptor =
        SerialDescriptor("${generated.descriptor.serialName}.Passthrough", generated.descriptor)

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: error("Records are only read from JSON.")
        val element = input.decodeJsonElement().jsonObject
        val value = input.json.decodeFromJsonElement(generated, JsonObject(element.filterKeys { it in known }))
        return withExtra(value, JsonObject(element.filterKeys { it !in known }))
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder ?: error("Records are only written as JSON.")
        val element = output.json.encodeToJsonElement(generated, value).jsonObject
        output.encodeJsonElement(JsonObject(element + extraOf(value)))
    }
}
