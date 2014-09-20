require 'haml'

module Helper
  def haml(template, layout)
    Haml::Engine.new(File.read(layout)).render do
      Haml::Engine.new(File.read(template)).render
    end    
  end
end