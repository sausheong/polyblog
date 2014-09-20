Sequel.migration do
  change do  
    create_table :posts do
      primary_key :id
      String :uuid, unique: true
      DateTime :created_at
      String :title
      String :content, text: true      
    end
  end  
end