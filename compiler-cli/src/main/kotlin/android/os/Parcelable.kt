package android.os

/**
 * Shim mínimo de [android.os.Parcel]/[android.os.Parcelable] — só pra `ConfigPOJO.Info` compilar.
 * Nunca exercido em runtime no compiler-cli: `WatchfaceConfig` é desserializado via Gson (reflexão
 * sobre o construtor sem args), que nunca chama writeToParcel/o construtor(Parcel).
 */
class Parcel {
    fun readString(): String = throw UnsupportedOperationException("Parcel não é usado no compiler-cli")
    fun writeString(value: String?) { throw UnsupportedOperationException("Parcel não é usado no compiler-cli") }
}

interface Parcelable {
    fun writeToParcel(dest: Parcel, flags: Int)
    fun describeContents(): Int

    interface Creator<T> {
        fun createFromParcel(source: Parcel): T
        fun newArray(size: Int): Array<T?>
    }
}
