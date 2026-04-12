package de.polocloud.database.exeption

class FactoryNotPresentException : RuntimeException("No factory present for the given DatabaseKey. Please ensure that a factory is registered for this key before performing database operations.") {
}