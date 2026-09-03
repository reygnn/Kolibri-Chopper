# The Chopper uses no reflection, no serialization, no Parcelable-by-name —
# so there is nothing to keep beyond what AGP's default manifest keep rules
# already retain (MainActivity is referenced from the manifest). R8 full mode
# is free to strip everything else.
