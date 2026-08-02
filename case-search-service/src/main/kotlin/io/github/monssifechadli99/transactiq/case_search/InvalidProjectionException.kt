package io.github.monssifechadli99.transactiq.case_search
class InvalidProjectionException(message:String):RuntimeException(message)
class ProjectionIntegrityException(message:String):RuntimeException(message)
class OpenSearchUnavailableException(message:String,cause:Throwable?=null):RuntimeException(message,cause)
