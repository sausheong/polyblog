require 'securerandom'
require 'bundler'
Bundler.require
require './ruby_helper'

include Helper

broker = "tcp://localhost:4321"
routeid = "GET/_/post/new"
identity = SecureRandom.uuid

puts "#{routeid} - (#{identity}) responder ready."

ctx = ZMQ::Context.new
client = ctx.socket ZMQ::REQ
client.identity = identity
client.connect broker

client.send_string routeid

loop do
  request = String.new
  client.recv_string request
  content = haml("views/post.new.haml", "views/layout.haml")
  response = [routeid, "200", "{\"Content-Type\": \"text/html\"}", content]
  client.send_strings response
end
  



