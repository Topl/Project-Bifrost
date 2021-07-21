package co.topl.utils

package object codecs {

  object implicits
      extends AsBytes.Instances
      with AsBytes.ToOps
      with FromBytes.Instances
      with FromBytes.ToOps
      with CryptoCodec.CryptoCodecInstances
      with StringDataTypesCodec.StringTypesInstances
      with SizedByteVectorCodec.Instances
}
