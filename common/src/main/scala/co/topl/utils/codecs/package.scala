package co.topl.utils

package object codecs {
  type InfallibleAsBytes[Decoded] = AsBytes[Infallible, Decoded]
  type InfallibleFromBytes[Decoded] = FromBytes[Infallible, Decoded]

  object implicits extends AsBytes.ToOps with AsBytes.Instances with FromBytes.ToOps with FromBytes.Instances
}
